from gguf import GGUFReader
from tokenizers import Tokenizer, models, pre_tokenizers, decoders
def load_tok(path):
    r = GGUFReader(path)
    f = r.fields['tokenizer.ggml.tokens']; toks = [bytes(f.parts[i]).decode() for i in f.data]
    f = r.fields['tokenizer.ggml.merges']; merges = [tuple(bytes(f.parts[i]).decode().split(' ', 1)) for i in f.data]
    t = Tokenizer(models.BPE({s: i for i, s in enumerate(toks)}, merges, unk_token='[UNK]'))
    t.pre_tokenizer = pre_tokenizers.ByteLevel(add_prefix_space=False, use_regex=True)
    t.decoder = decoders.ByteLevel()
    return t
