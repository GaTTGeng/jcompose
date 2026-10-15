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

The repository does not yet have a completed Maven Central publishing workflow. Before the release step, confirm ownership of the `io.github.gattgeng.jcompose` namespace, choose the Maven repository, configure artifact signing and CI publication credentials, and exercise the workflow without publishing a release. Add the exact workflow and recovery procedure here once it exists. Do not create a release tag or publish JDK artifacts while this section is incomplete.

## Release execution

When every gate is satisfied, use the repository's documented CI release workflow and a plain SemVer tag. Do not create the first JDK release as a side effect of feature development. A failed or partial publication must be reconciled against the repository host's immutable-version rules before retrying.
