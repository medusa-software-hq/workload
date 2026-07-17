# integration-test fixtures

GCP fixtures for [`integration-test-registry-auth.yml`](../../.github/workflows/integration-test-registry-auth.yml),
the manually-triggered workflow that proves the **brokered pull** works against a real
private Artifact Registry image.

Everything else about that path is unit- and contract-tested. The one thing no
automated test could reach was the live GCP hop: does a token minted by
impersonating a profile's target service account actually pull a private image,
and is it actually refused when that account lacks
`roles/artifactregistry.reader`? This is what makes that testable.

## Why the CI identity isn't the identity under test

`integration-test-ci` is close to an admin in this project, so a pull
authenticated *as it* would succeed no matter what the reader grant said — and
the negative case could never fail, making the whole test permanently green and
worthless.

So the CI identity is only a front door. It holds
`roles/iam.serviceAccountTokenCreator` on two target accounts — **the same role
the broker's runtime SA holds on a real target SA** (see
[workload-impersonation](../modules/workload-impersonation)) — and the workflow
mints a token for each. The token under test is therefore a genuine target-SA
token, produced by the production mechanism:

| Account | `artifactregistry.reader` | Expected |
| --- | --- | --- |
| `it-reader` | ✅ granted | the brokered pull succeeds |
| `it-no-reader` | ❌ not granted | 403 → our typed error + the remediation hint, and the digest resolver flags the revision `UNRESOLVABLE` |

The two accounts are identical apart from that one grant, which is the only
variable the test measures.

## Applying

Manual, against the throwaway `workload - test` project — like
[`worker-mvp/infra`](../../worker-mvp/infra) there is deliberately no apply
workflow, because these fixtures change roughly never and shouldn't ride the
deploy path.

```bash
cd infra/integration-test
terraform init
terraform apply     # default var: gcp_project_id = ms-workload-test-78f9932a
```

It writes the `IT_*` Actions variables the workflow reads, so after an apply the
workflow is ready to run with no further wiring.

## Teardown

`terraform destroy`. Nothing here is referenced by the product — only by the
integration-test workflow, which self-skips when the `IT_*` variables are absent.
