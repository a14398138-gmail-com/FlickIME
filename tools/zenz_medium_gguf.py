#!/usr/bin/env python3
"""Builds app/src/main/assets/zenz-medium.gguf (zenz-v2.5-medium, Q5_K_M) from Hugging Face.

The file (~232 MB) is too big for git, so it is not in the repository. Run from the repo root:
  pip install gguf llama-cpp-python
  mkdir -p /tmp/zmed && cd /tmp/zmed && for f in config.json model.safetensors; do curl -fsSLO https://huggingface.co/Miwa-Keita/zenz-v2.5-medium/resolve/main/$f; done
  python3 tools/zenz_medium_gguf.py /tmp/zmed app/src/main/assets/zenz.gguf app/src/main/assets/zenz-medium.gguf
The tokenizer is copied from the small v3.1 model (same 6000-token vocabulary). Model: CC BY-SA 4.0 (Keita Miwa).
"""
import sys, os
src, small, dst = sys.argv[1], os.path.abspath(sys.argv[2]), os.path.abspath(sys.argv[3])
os.chdir(src)
import json, struct, numpy as np
from gguf import GGUFReader, GGUFWriter
def load_st(path):
    with open(path,'rb') as f:
        n=struct.unpack('<Q',f.read(8))[0]; hdr=json.loads(f.read(n)); base=8+n
        out={}
        for k,m in hdr.items():
            if k=='__metadata__': continue
            s,e=m['data_offsets']; f.seek(base+s); b=f.read(e-s)
            if m['dtype']=='BF16': a=(np.frombuffer(b,np.uint16).astype(np.uint32)<<16).view(np.float32)
            elif m['dtype']=='F32': a=np.frombuffer(b,np.float32)
            else: raise Exception(m['dtype'])
            out[k]=a.reshape(m['shape'])
    return out
W=load_st('model.safetensors'); print(len(W), list(W)[:6])
cfg=json.load(open('config.json'))
r=GGUFReader(small)
w=GGUFWriter('medium-f16.gguf','gpt2')
w.add_name('zenz-v2.5-medium'); w.add_block_count(cfg['n_layer']); w.add_context_length(cfg['n_ctx'])
w.add_embedding_length(cfg['n_embd']); w.add_feed_forward_length(4*cfg['n_embd']); w.add_head_count(cfg['n_head'])
w.add_layer_norm_eps(cfg['layer_norm_epsilon']); w.add_file_type(1)
w.add_tokenizer_model('gpt2'); w.add_tokenizer_pre('gpt2-small-japanese-char')
w.add_token_list(r.fields['tokenizer.ggml.tokens'].contents())
w.add_token_types(r.fields['tokenizer.ggml.token_type'].contents())
w.add_token_merges(r.fields['tokenizer.ggml.merges'].contents())
w.add_bos_token_id(1); w.add_eos_token_id(2); w.add_pad_token_id(1); w.add_add_bos_token(False)
def p(k): return W.get(k, W.get('transformer.'+k))
def add(name, a, f16=True):
    a=np.ascontiguousarray(a)
    w.add_tensor(name, a.astype(np.float16) if (f16 and a.ndim==2) else a.astype(np.float32))
add('token_embd.weight', p('wte.weight')); add('position_embd.weight', p('wpe.weight'))
for i in range(cfg['n_layer']):
    b=f'h.{i}.'
    add(f'blk.{i}.attn_norm.weight', p(b+'ln_1.weight')); add(f'blk.{i}.attn_norm.bias', p(b+'ln_1.bias'))
    add(f'blk.{i}.attn_qkv.weight', p(b+'attn.c_attn.weight').T); add(f'blk.{i}.attn_qkv.bias', p(b+'attn.c_attn.bias'))
    add(f'blk.{i}.attn_output.weight', p(b+'attn.c_proj.weight').T); add(f'blk.{i}.attn_output.bias', p(b+'attn.c_proj.bias'))
    add(f'blk.{i}.ffn_norm.weight', p(b+'ln_2.weight')); add(f'blk.{i}.ffn_norm.bias', p(b+'ln_2.bias'))
    add(f'blk.{i}.ffn_up.weight', p(b+'mlp.c_fc.weight').T); add(f'blk.{i}.ffn_up.bias', p(b+'mlp.c_fc.bias'))
    add(f'blk.{i}.ffn_down.weight', p(b+'mlp.c_proj.weight').T); add(f'blk.{i}.ffn_down.bias', p(b+'mlp.c_proj.bias'))
add('output_norm.weight', p('ln_f.weight')); add('output_norm.bias', p('ln_f.bias'))
add('output.weight', W['lm_head.weight'] if 'lm_head.weight' in W else p('wte.weight'))
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print('done')

import llama_cpp, ctypes
p = llama_cpp.llama_model_quantize_default_params(); p.ftype = 17  # Q5_K_M
assert llama_cpp.llama_model_quantize(b'medium-f16.gguf', dst.encode(), ctypes.byref(p)) == 0
print('wrote', dst)
