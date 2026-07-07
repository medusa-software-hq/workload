# Terraform configuration for the web app

Split into two roots because the domain mapping must be applied by a different
identity than the rest of the web infrastructure:

| Directory         | State prefix                       | Applied by              | Manages                                              |
| ----------------- | ---------------------------------- | ----------------------- | ---------------------------------------------------- |
| `foundation/`     | `…/apps/web/foundation`            | this project's CI/CD SA | Cloud Run service (IAP-direct) + IAP access policy.  |
| `domain-mapping/` | `…/apps/web/domain-mapping`        | shared domain-mapper SA | Cloud Run domain mapping + the Cloudflare DNS record. |

Cloud Run domain mappings require the caller to be a verified owner of the
domain. Rather than make every project's CI/CD SA a domain owner, the mapping is
applied by the shared `github-actions-domain-mapper` service account (defined and
made a domain owner once in the `meta` repo). The `foundation` root is applied
first (it creates the service the mapping points at).
