#!/usr/bin/env bash

# Bounded retry wrapper for read-only `gh api` calls in protected workflows. Successful response
# bytes are the only stdout; diagnostics stay on stderr so callers may safely use command
# substitution or pipelines. Authentication/provenance validation remains the caller's job.
github_api_retry() {
  if (( $# == 0 )); then
    printf 'github_api_retry requires gh api arguments\n' >&2
    return 2
  fi

  local attempt api_status delay output
  delay=5
  for attempt in 1 2 3 4; do
    output=''
    api_status=0
    if output="$(gh api "$@" 2>&1)"; then
      printf '%s\n' "$output"
      return 0
    else
      api_status=$?
    fi

    if ! grep -Eqi \
        'API rate limit exceeded|secondary rate limit|HTTP (408|429|5[0-9][0-9])|connection (reset|refused)|timed out|timeout|temporary failure|TLS handshake|unexpected EOF' \
        <<< "$output"; then
      printf '%s\n' "$output" >&2
      return "$api_status"
    fi
    if (( attempt == 4 )); then
      printf '%s\n' "$output" >&2
      return "$api_status"
    fi

    # Run-bound skew prevents separate matrix jobs from retrying in lockstep.
    local skew=0
    if [[ "${GITHUB_RUN_ID:-}" =~ ^[1-9][0-9]*$ ]]; then
      skew=$(( (GITHUB_RUN_ID + attempt) % 5 ))
    fi
    printf 'GitHub API attempt %s/4 failed transiently; retrying in %ss.\n' \
      "$attempt" "$((delay + skew))" >&2
    sleep "$((delay + skew))"
    delay=$(( delay < 20 ? delay * 2 : 20 ))
  done
}
