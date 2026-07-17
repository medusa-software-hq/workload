#!/bin/sh
# Proves a `workload run` actually delivered what it promised: the profile's environment, the
# resolved secrets, and a brokered token that really works — without printing a single secret value.
#
# Why fingerprints and not values: this output is the container's stdout, which anyone with daemon
# access can read back with `docker logs`. Printing "the last 4 chars of the secret" would put real
# key material there. A sha256 prefix proves the value is *exactly* the one you expect — you compute
# the same hash locally and compare — and leaks nothing:
#
#     printf '%s' 'the-value-you-set' | sha256sum | cut -c1-12
#
# (A hash only fingerprints a *high-entropy* secret. Hashing "hunter2" is brute-forceable, so this
# is a check for real secrets, not a way to make a weak one safe.)
set -eu

# Never fingerprint these: they come from the image/daemon, not from the profile, and they're noise.
IGNORED_VARS="PATH HOME HOSTNAME PWD SHLVL TERM"

FINGERPRINT_CHARS="${HELLO_FINGERPRINT_CHARS:-12}"

banner() {
  echo "=============================================="
  echo " hello-workload"
  echo "=============================================="
}

fingerprint() {
  # $1 = value. Prints <sha256 prefix>.
  printf '%s' "$1" | sha256sum | cut -c1-"$FINGERPRINT_CHARS"
}

is_ignored() {
  for ignored in $IGNORED_VARS; do
    [ "$1" = "$ignored" ] && return 0
  done
  return 1
}

report_environment() {
  echo
  echo "environment (values are never printed — compare the fingerprint):"
  printf '  %-32s %6s  %s\n' "NAME" "LEN" "SHA256"

  # `env` one var per line; take only NAME=... lines so a multi-line value can't forge a row.
  env | sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' | sort | while read -r name; do
    is_ignored "$name" && continue
    value=$(eval "printf '%s' \"\${$name}\"")
    printf '  %-32s %6d  %s\n' "$name" "${#value}" "$(fingerprint "$value")"
  done
}

# The interesting one: the same token that authenticated the image pull should also authenticate
# GCP from *inside* the container — one identity end to end. Ask Google who it belongs to. The
# response carries no token, so it is safe to print.
report_identity() {
  echo
  token="${GOOGLE_OAUTH_ACCESS_TOKEN:-}"
  if [ -z "$token" ]; then
    echo "identity: no GOOGLE_OAUTH_ACCESS_TOKEN in the environment"
    return
  fi

  response=$(curl -sS --max-time 10 \
    "https://oauth2.googleapis.com/tokeninfo?access_token=${token}" 2>&1) || {
    echo "identity: could not reach Google's tokeninfo endpoint (no network?)"
    return
  }

  email=$(echo "$response" | sed -n 's/.*"email"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  expires=$(echo "$response" | sed -n 's/.*"expires_in"[[:space:]]*:[[:space:]]*"\{0,1\}\([0-9]*\).*/\1/p')

  if [ -n "$email" ]; then
    echo "identity: the brokered token is live and belongs to"
    echo "  $email"
    [ -n "$expires" ] && echo "  (expires in ${expires}s)"
    echo "  -> the same token pulled this image and can act as this SA from in here."
  else
    echo "identity: Google rejected the brokered token (expired, or not an access token)"
  fi
}

banner
echo "arch:  $(uname -m)"
report_identity
report_environment

# Lets a profile drive the exit code from its env — so "exit code passthrough" is testable by hand
# without needing a second image.
exit_code="${HELLO_EXIT_CODE:-0}"
echo
echo "exiting with ${exit_code}"
exit "$exit_code"
