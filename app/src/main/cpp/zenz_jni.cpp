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

constexpr llama_token END = 3;  // "</s>": what zenz emits after an output

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
    cp.n_ctx = 512;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_seq_max = 1;
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
            std::vector<float> first(llama_get_logits_ith(z->ctx, -1), llama_get_logits_ith(z->ctx, -1) + z->n_vocab);
            const int np = (int) prompt.size();
            for (jsize i = 0; i < n; i++) {
                auto *ja = (jbyteArray) env->GetObjectArrayElement(jcands, i);
                auto c = tokenize(*z, bytes(env, ja));
                env->DeleteLocalRef(ja);
                c.push_back(END);
                if (np + (int) c.size() >= 500) continue;
                float nll = -logprob(first.data(), z->n_vocab, c[0]);
                if (c.size() > 1) {
                    llama_memory_seq_rm(mem, 0, np, -1);
                    std::vector<llama_token> feed(c.begin(), c.end() - 1);
                    if (!decode(*z, feed, np, true)) continue;
                    for (size_t k = 0; k + 1 < c.size(); k++)
                        nll -= logprob(llama_get_logits_ith(z->ctx, (int32_t) k), z->n_vocab, c[k + 1]);
                }
                out[(size_t) i] = nll;
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
