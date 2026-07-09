const PROFILE_ID_PATTERN = /^[a-z0-9-]{3,63}$/;
const SERVICE_ACCOUNT_PATTERN = /^[a-zA-Z0-9-]+@[a-zA-Z0-9-]+\.iam\.gserviceaccount\.com$/;

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
