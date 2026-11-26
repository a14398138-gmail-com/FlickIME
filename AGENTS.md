# FlickIME — notes for coding agents

- Android Japanese flick IME for one user. Engine (pure Kotlin, no Android deps): `app/src/main/java/dev/flickime/engine/`.
- Evaluate without Gradle/Android SDK (JDK only): `tools/loop/setup.sh` once, then `tools/loop/eval_all.sh dev <tag>`.
- Improvement-loop instructions (conversion ranking, "no scrolling the candidate bar"): `docs/GOAL_opencode.md`.
- Prediction (ksr) loop instructions: `docs/GOAL_predict.md`.
- Never commit or quote `eval/` or `out/` (the author's private text). Never push.
