const PROFILE_ID_PATTERN = /^[a-z0-9-]{3,63}$/;
const SERVICE_ACCOUNT_PATTERN = /^[a-zA-Z0-9-]+@[a-zA-Z0-9-]+\.iam\.gserviceaccount\.com$/;
const ENV_VAR_NAME_PATTERN = /^[A-Z_][A-Z0-9_]*$/;
const SECRET_RESOURCE_NAME_PATTERN = /^projects\/[^/]+\/secrets\/[^/]+\/versions\/(latest|\d+)$/;
// A fully-qualified registry ref: a host with a dot or port before the first slash, then a path.
// Mirrors the backend's imageRefPattern (FleetServiceImpl.kt) — bare Docker Hub shorthand is out.
const IMAGE_REF_PATTERN = /^[^\s/]+[.:][^\s/]*\/\S+$/;
// Only Google's registries. This mirrors the backend's isGoogleRegistryHost, and it is a security
// rule: both the backend (resolving the digest) and the worker (pulling) authenticate to the
// image's registry with a token impersonating the profile's target service account, so an
// arbitrary host would be handed a live credential for that account.
const GOOGLE_REGISTRY_PATTERN = /(^gcr\.io$)|(\.gcr\.io$)|(\.pkg\.dev$)/;

/** Mirrors the backend's ProfileId validation (FleetModel.kt) — kept in sync by hand. */
export function validateProfileId(id: string): string | null {
  if (!PROFILE_ID_PATTERN.test(id)) {
    return 'Must be 3–63 characters: lowercase letters, digits, or hyphens.';
  }
  if (id.startsWith('-') || id.endsWith('-')) {
    return 'Must not start or end with a hyphen.';
  }
  return null;
}

export function validateServiceAccount(email: string): string | null {
  return SERVICE_ACCOUNT_PATTERN.test(email)
    ? null
    : 'Must look like a GCP service account email (name@project.iam.gserviceaccount.com).';
}

/** Mirrors the backend's env var name validation (FleetServiceImpl.kt). */
export function validateEnvVarName(name: string): string | null {
  return ENV_VAR_NAME_PATTERN.test(name)
    ? null
    : 'Must be uppercase letters, digits, or underscores, starting with a letter or underscore.';
}

/** Mirrors the backend's secret_env_vars resource-name format check. */
export function validateSecretResourceName(resourceName: string): string | null {
  return SECRET_RESOURCE_NAME_PATTERN.test(resourceName)
    ? null
    : 'Must look like projects/<project>/secrets/<secret>/versions/latest (or a pinned version number).';
}

/**
 * Mirrors the backend's docker_image validation (FleetServiceImpl.kt). A blank ref is valid — it
 * means "no image" (a pure exec/env profile).
 */
export function validateImageRef(imageRef: string): string | null {
  if (imageRef.trim() === '') {
    return null;
  }
  const trimmed = imageRef.trim();
  if (!IMAGE_REF_PATTERN.test(trimmed)) {
    return 'Must be a fully-qualified registry ref, e.g. LOCATION-docker.pkg.dev/PROJECT/REPO/IMAGE:TAG.';
  }
  const host = trimmed.split('/')[0];
  if (!GOOGLE_REGISTRY_PATTERN.test(host.toLowerCase())) {
    return `Must live in a Google container registry (*.pkg.dev, gcr.io, *.gcr.io) — '${host}' is not one.`;
  }
  return null;
}
