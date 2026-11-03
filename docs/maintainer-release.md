# Maintainer release guide

This repository targets JDK users and Maven artifacts. **Publishing a JDK release is the final project step**: finish feature parity, validate Docker Compose behavior, prepare the repository and CI, and review release contents before creating a release tag or publishing artifacts.

## Repository readiness

- Keep the default branch buildable and protect it with the configured CI checks.
- Keep the repository public and ensure its README, license, documentation, and issue links describe jcompose and Docker Compose accurately.
- Do not track local IDE files or the local-only `AGENTS.md` maintenance instructions.
- Ensure CI builds all Maven modules with the supported JDK and runs the conformance suite.
- Configure signing and publication credentials as GitHub Actions secrets or trusted OIDC credentials; never commit credentials.

## Release gates

1. Complete the ComposeSharp 3.0.0 baseline and the explicitly selected ComposeSharp sync checkpoint in jcompose, while independently validating compatibility against Docker Compose. Record the exact ComposeSharp version or commit used for the release scope.
2. Update the [field support matrix](compose-field-matrix.md), [merge semantics](merge-semantics.md), [generate/watch contract](generate-watch.md), and [roadmap](roadmap.md) to match actual code and evidence.
3. Run the full Maven reactor verification on the supported JDK, including Docker-backed integration coverage where available.
4. Confirm the release version, dependency versions, licenses, source/Javadoc artifacts, signing, and publication workflow.
5. Review the exact commit and generated artifacts. Only then tag and publish the JDK artifacts.

## Publication configuration

Maven Central's Sonatype Central Portal is the selected artifact host. The reactor attaches source and Javadoc jars during `package`. The `release` Maven profile signs artifacts with GnuPG and uploads them to a Central deployment for validation; automatic public release is disabled, so a maintainer must review the validated deployment in the Central Portal and explicitly publish it.

The [Maven release workflow](../.github/workflows/publish-maven.yml) runs only for a plain SemVer tag such as `3.0.0`. It checks that the root project and every module parent reference use the same non-snapshot version, imports the signing key, runs the complete Maven `deploy` lifecycle, and waits for Central validation. It neither creates tags nor automatically publishes a validated deployment.

Before creating the release tag, register and verify the `io.github.gattgeng.jcompose` namespace in the Sonatype Central Portal, add a protected GitHub environment named `maven-central` with a reviewer, and configure these environment secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`, and `MAVEN_GPG_KEY_FINGERPRINT`. Keep the release tag's POM versions aligned across all modules. The workflow has not been run against Central and must not be triggered until the final JDK release step.

If a deployment fails validation, correct the source and release metadata and use a new version before retrying. After Central publishes a version, treat it as immutable; reconcile any partial upload in the Portal before attempting another release.

## Release execution

When every gate is satisfied, use the repository's documented CI release workflow and a plain SemVer tag. Do not create the first JDK release as a side effect of feature development. A failed or partial publication must be reconciled against the repository host's immutable-version rules before retrying.
