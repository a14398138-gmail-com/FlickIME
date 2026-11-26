# Minimal GPT-2 that loads / saves zenz GGUF weights (gpt2 arch), for fine-tuning on CPU.
import numpy as np, torch, torch.nn as nn, torch.nn.functional as F
from gguf import GGUFReader, GGUFWriter, GGMLQuantizationType as Q
from gguf.quants import dequantize, quantize

def load_tensors(path):
    r = GGUFReader(path)
    ts = {}
    for t in r.tensors:
        a = dequantize(t.data, t.tensor_type)
        shape = tuple(int(x) for x in reversed(t.shape))
        ts[t.name] = np.ascontiguousarray(a.reshape(shape)).astype(np.float32)
    return r, ts

class Block(nn.Module):
    def __init__(s, d, h):
        super().__init__(); s.h = h
        s.ln1 = nn.LayerNorm(d); s.qkv = nn.Linear(d, 3 * d); s.proj = nn.Linear(d, d)
        s.ln2 = nn.LayerNorm(d); s.up = nn.Linear(d, 4 * d); s.down = nn.Linear(4 * d, d)
    def forward(s, x):
        B, T, D = x.shape
        q, k, v = s.qkv(s.ln1(x)).split(D, 2)
        q, k, v = (z.view(B, T, s.h, D // s.h).transpose(1, 2) for z in (q, k, v))
        a = F.scaled_dot_product_attention(q, k, v, is_causal=True).transpose(1, 2).reshape(B, T, D)
        x = x + s.proj(a)
        return x + s.down(F.gelu(s.up(s.ln2(x)), approximate='tanh'))

class GPT2(nn.Module):
    def __init__(s, ts, n_layer=12, heads=12):
        super().__init__()
        V, D = ts['token_embd.weight'].shape
        s.wte = nn.Embedding(V, D); s.wpe = nn.Embedding(ts['position_embd.weight'].shape[0], D)
        s.blocks = nn.ModuleList(Block(D, heads) for _ in range(n_layer))
        s.lnf = nn.LayerNorm(D); s.head = nn.Linear(D, V, bias=False)
        s.names = {}
        def put(p, name): p.data = torch.from_numpy(ts[name].copy()); s.names[name] = p
        put(s.wte.weight, 'token_embd.weight'); put(s.wpe.weight, 'position_embd.weight')
        for i, b in enumerate(s.blocks):
            for mod, nm in ((b.ln1, 'attn_norm'), (b.qkv, 'attn_qkv'), (b.proj, 'attn_output'), (b.ln2, 'ffn_norm'), (b.up, 'ffn_up'), (b.down, 'ffn_down')):
                put(mod.weight, f'blk.{i}.{nm}.weight'); put(mod.bias, f'blk.{i}.{nm}.bias')
        put(s.lnf.weight, 'output_norm.weight'); put(s.lnf.bias, 'output_norm.bias')
        put(s.head.weight, 'output.weight')
    def forward(s, idx):
        T = idx.shape[1]
        x = s.wte(idx) + s.wpe(torch.arange(T))
        for b in s.blocks: x = b(x)
        return s.head(s.lnf(x))

def save_gguf(model, reader, out, qtype=Q.Q8_0):
    """Same metadata and tensor order as the original; 2-D weights quantized to qtype, the rest F32."""
    w = GGUFWriter(out, arch='gpt2')
    for k, f in reader.fields.items():
        if k.startswith('GGUF.') or k == 'general.architecture': continue
        vt = f.types[0]
        if vt.name == 'ARRAY':
            sub = f.types[1]
            vals = [bytes(f.parts[i]).decode('utf-8') if sub.name == 'STRING' else f.parts[i].tolist()[0] for i in f.data]
            w.add_key_value(k, vals, vt, sub_type=sub)
        elif vt.name == 'STRING':
            w.add_key_value(k, bytes(f.parts[f.data[0]]).decode('utf-8'), vt)
        else:
            v = f.parts[f.data[0]].tolist()[0]
            if k == 'general.file_type': v = 7 if qtype == Q.Q8_0 else v
            w.add_key_value(k, v, vt)
    for t in reader.tensors:
        a = model.names[t.name].detach().numpy().astype(np.float32)
        if a.ndim == 2 and qtype is not None:
            qa = quantize(a, qtype)
            w.add_tensor(t.name, qa, raw_dtype=qtype)
        else:
            w.add_tensor(t.name, a)
    w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
