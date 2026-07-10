const PROFILE_ID_PATTERN = /^[a-z0-9-]{3,63}$/;
const SERVICE_ACCOUNT_PATTERN = /^[a-zA-Z0-9-]+@[a-zA-Z0-9-]+\.iam\.gserviceaccount\.com$/;
const ENV_VAR_NAME_PATTERN = /^[A-Z_][A-Z0-9_]*$/;
const SECRET_RESOURCE_NAME_PATTERN = /^projects\/[^/]+\/secrets\/[^/]+\/versions\/(latest|\d+)$/;

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
