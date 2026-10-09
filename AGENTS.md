# 6ubaaFP project rules

- Author: SpidiBoost. Mod Menu name: spidiboost.6ubaaFP. Java 21, Minecraft 1.21.4, Fabric; standalone except Fabric API.
- Publish to SpidiBoostMods/6ubaaFP, using SpidiBoostDev. Preserve other GitHub accounts.
- User distribution policy: publish completed releases to GitHub after verification; do not install or replace JARs in the user's Prism instances.
- JAR names: 6ubaaFP-1.21.4-<mod_version>.jar; metadata and filename versions must match.
- Run local checks, native client integration for UI/behavior changes, and Windows/macOS/Linux CI before publication. Compilation alone is not runtime proof.
- Increment mod_version, commit and push an annotated release tag; verify public manifest, downloaded identity and SHA-256.
- Shared updater copies are generated from the SpidiCard src/sharedTemplate sources; keep protocol and catalog identical across the four mods. Each relocated copy participates in one ObjectShare election.
- Never publish credentials, live instances, player results, logs, configs or launcher arguments. Keep licenses for upstream assets.
- Do not touch AdminTools or user recordings. Use isolated runtime verification.
- Update only installed family JARs; never rewrite a JAR used by the live client. With update off a verified new version may be downloaded as an additional JAR in mods only after an exit helper is ready to remove the old version after exit. Preserve configs/results/other mods and backups. Hist addon removal is allowed only after its complete migration into SpidiBan.
