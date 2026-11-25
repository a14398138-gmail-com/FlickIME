// JNI bridge: scores conversion candidates with the zenz model through llama.cpp.
#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "zenz", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif
#include <cmath>
#include <string>
#include <vector>
#include "llama.h"


namespace {
struct Zenz {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_vocab = 0;
};

constexpr llama_token END = 3;
constexpr int MAX_SEQ = 16;  // "</s>": what zenz emits after an output

std::vector<llama_token> tokenize(const Zenz &z, const std::string &text) {
    std::vector<llama_token> out(text.size() + 8);
    int n = llama_tokenize(z.vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, false);
    if (n < 0) {
        out.resize(-n);
        n = llama_tokenize(z.vocab, text.data(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, false);
    }
    out.resize(n > 0 ? n : 0);
    return out;
}

/** log-softmax value of token t in logits */
float logprob(const float *logits, int n_vocab, llama_token t) {
    float mx = logits[0];
    for (int i = 1; i < n_vocab; i++) if (logits[i] > mx) mx = logits[i];
    double sum = 0;
    for (int i = 0; i < n_vocab; i++) sum += std::exp((double) (logits[i] - mx));
    return (float) (logits[t] - mx - std::log(sum));
}

std::string bytes(JNIEnv *env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string s((size_t) n, '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

bool decode(Zenz &z, const std::vector<llama_token> &toks, int pos0, bool all_logits) {
    llama_batch b = llama_batch_init((int32_t) toks.size(), 0, 1);
    for (size_t i = 0; i < toks.size(); i++) {
        b.token[i] = toks[i];
        b.pos[i] = pos0 + (int) i;
        b.n_seq_id[i] = 1;
        b.seq_id[i][0] = 0;
        b.logits[i] = all_logits || i + 1 == toks.size();
    }
    b.n_tokens = (int32_t) toks.size();
    int rc = llama_decode(z.ctx, b);
    llama_batch_free(b);
    return rc == 0;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_flickime_engine_Zenz_nativeLoad(JNIEnv *env, jclass, jstring path, jint threads) {
    llama_backend_init();
    const char *p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!model) { LOGE("model load failed"); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 1024;  // prompt + prefix tree; KV of the medium model is ~100 MB at this size
    cp.n_batch = 1024;
    cp.n_ubatch = 1024;
    cp.n_seq_max = MAX_SEQ;
    cp.kv_unified = true;  // candidates share the prompt
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }
    auto *z = new Zenz();
    z->model = model;
    z->ctx = ctx;
    z->vocab = llama_model_get_vocab(model);
    z->n_vocab = llama_vocab_n_tokens(z->vocab);
    return reinterpret_cast<jlong>(z);
}

/**
 * Negative log-likelihood (nats) of each candidate as the output for [prompt]
 * (prompt = [ context]  katakana , UTF-8). NaN on failure.
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_flickime_engine_Zenz_nativeScore(JNIEnv *env, jclass, jlong handle, jbyteArray jprompt, jobjectArray jcands) {
    auto *z = reinterpret_cast<Zenz *>(handle);
    jsize n = env->GetArrayLength(jcands);
    std::vector<float> out((size_t) n, NAN);
    if (z) {
        // no BOS: the model's metadata disables it, and azooKey's llama_tokenize call adds none either
        auto prompt = tokenize(*z, bytes(env, jprompt));
        llama_memory_t mem = llama_get_memory(z->ctx);
        llama_memory_clear(mem, true);
        if ((int) prompt.size() < 400 && decode(*z, prompt, 0, false)) {
            // distribution of the first output token, shared by all candidates
            const float *l0 = llama_get_logits_ith(z->ctx, -1);
            std::vector<float> first(l0, l0 + z->n_vocab);
            const int np = (int) prompt.size();
            std::vector<std::vector<llama_token>> cand((size_t) n);
            for (jsize i = 0; i < n; i++) {
                auto *ja = (jbyteArray) env->GetObjectArrayElement(jcands, i);
                cand[i] = tokenize(*z, bytes(env, ja));
                env->DeleteLocalRef(ja);
                cand[i].push_back(END);
            }
            // All candidates in ONE batch, as a prefix tree: a token shared by several candidates
            // (same text so far) is computed once and belongs to all their sequences.
            struct Node { llama_token tok; int pos; std::vector<int> seqs; int child_start; };
            std::vector<Node> nodes;
            std::vector<std::vector<int>> path((size_t) n);   // batch index of each fed token per candidate
            std::vector<bool> usable((size_t) n, false);
            for (jsize i = 0; i < n && i + 1 < MAX_SEQ; i++) {
                const auto &c = cand[i];
                if (np + (int) c.size() > 500) continue;
                usable[i] = true;
                int parent = -1;
                for (size_t k = 0; k + 1 < c.size(); k++) {
                    int found = -1;
                    // children of `parent` are nodes created with that parent; search linearly (tiny)
                    for (size_t q = 0; q < nodes.size(); q++)
                        if (nodes[q].child_start == parent && nodes[q].tok == c[k] && nodes[q].pos == np + (int) k) { found = (int) q; break; }
                    if (found < 0) {
                        if ((int) nodes.size() + np >= 1016) { usable[i] = false; break; }
                        nodes.push_back({c[k], np + (int) k, {}, parent});
                        found = (int) nodes.size() - 1;
                    }
                    nodes[found].seqs.push_back(i + 1);
                    path[i].push_back(found);
                    parent = found;
                }
            }
            for (jsize i = 0; i < n && i + 1 < MAX_SEQ; i++) if (usable[i] && cand[i].size() > 1) llama_memory_seq_cp(mem, 0, i + 1, -1, -1);
            llama_batch b = llama_batch_init(1024, 0, MAX_SEQ);
            for (size_t q = 0; q < nodes.size(); q++) {
                b.token[q] = nodes[q].tok;
                b.pos[q] = nodes[q].pos;
                b.n_seq_id[q] = (int32_t) nodes[q].seqs.size();
                for (size_t t = 0; t < nodes[q].seqs.size(); t++) b.seq_id[q][t] = nodes[q].seqs[t];
                b.logits[q] = true;
            }
            b.n_tokens = (int32_t) nodes.size();
            bool ok = nodes.empty() || llama_decode(z->ctx, b) == 0;
            llama_batch_free(b);
            if (ok) {
                for (jsize i = 0; i < n; i++) {
                    if (!usable[i] || (cand[i].size() > 1 && path[i].size() + 1 != cand[i].size())) continue;
                    const auto &c = cand[i];
                    float nll = -logprob(first.data(), z->n_vocab, c[0]);
                    for (size_t k = 0; k < path[i].size(); k++)
                        nll -= logprob(llama_get_logits_ith(z->ctx, path[i][k]), z->n_vocab, c[k + 1]);
                    out[(size_t) i] = nll;
                }
            }
        }
    }
    jfloatArray res = env->NewFloatArray(n);
    env->SetFloatArrayRegion(res, 0, n, out.data());
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_flickime_engine_Zenz_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *z = reinterpret_cast<Zenz *>(handle);
    if (!z) return;
    llama_free(z->ctx);
    llama_model_free(z->model);
    delete z;
}
