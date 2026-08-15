#!/usr/bin/env bash

# Bounded retry wrappers for GitHub CLI calls in protected workflows. Successful textual responses
# are the only stdout; diagnostics stay on stderr so callers may safely use command substitution or
# pipelines. Authentication/provenance validation remains the caller's job.
github_cli_retry() {
  if (( $# == 0 )); then
    printf 'github_cli_retry requires a command\n' >&2
    return 2
  fi

  local max_attempts="${GITHUB_API_RETRY_ATTEMPTS:-4}"
  local max_delay="${GITHUB_API_RETRY_MAX_DELAY_SECONDS:-20}"
  if [[ ! "$max_attempts" =~ ^[1-9][0-9]*$ ]] \
      || (( max_attempts > 30 )) \
      || [[ ! "$max_delay" =~ ^[1-9][0-9]*$ ]] \
      || (( max_delay > 300 )); then
    printf 'invalid GitHub API retry bounds\n' >&2
    return 2
  fi

  local attempt api_status delay output
  delay=$(( max_delay < 5 ? max_delay : 5 ))
  for (( attempt = 1; attempt <= max_attempts; attempt++ )); do
    output=''
    api_status=0
    if output="$("$@" 2>&1)"; then
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
    if (( attempt == max_attempts )); then
      printf '%s\n' "$output" >&2
      return "$api_status"
    fi

    # Run-bound skew prevents separate matrix jobs from retrying in lockstep.
    local skew=0
    if [[ "${GITHUB_RUN_ID:-}" =~ ^[1-9][0-9]*$ ]]; then
      skew=$(( (GITHUB_RUN_ID + attempt) % 5 ))
    fi
    printf 'GitHub API attempt %s/%s failed transiently; retrying in %ss.\n' \
      "$attempt" "$max_attempts" "$((delay + skew))" >&2
    sleep "$((delay + skew))"
    delay=$(( delay < max_delay ? delay * 2 : max_delay ))
    if (( delay > max_delay )); then
      delay="$max_delay"
    fi
  done
}

github_api_retry() {
  if (( $# == 0 )); then
    printf 'github_api_retry requires gh api arguments\n' >&2
    return 2
  fi
  github_cli_retry gh api "$@"
}

# Binary artifact downloads must never pass through command substitution: Bash variables cannot
# preserve NUL bytes. Stream each attempt into a fresh sibling file and publish it atomically only
# after `gh api` succeeds, leaving an existing destination untouched on failure.
github_api_retry_to_file() {
  if (( $# < 2 )) || [[ -z "$1" ]]; then
    printf 'github_api_retry_to_file requires a destination and gh api arguments\n' >&2
    return 2
  fi

  local destination="$1"
  shift
  local max_attempts="${GITHUB_API_RETRY_ATTEMPTS:-4}"
  local max_delay="${GITHUB_API_RETRY_MAX_DELAY_SECONDS:-20}"
  if [[ ! "$max_attempts" =~ ^[1-9][0-9]*$ ]] \
      || (( max_attempts > 30 )) \
      || [[ ! "$max_delay" =~ ^[1-9][0-9]*$ ]] \
      || (( max_delay > 300 )); then
    printf 'invalid GitHub API retry bounds\n' >&2
    return 2
  fi

  local attempt api_status delay diagnostic error_file partial_file
  partial_file="$(mktemp "${destination}.partial.XXXXXX")" || return 1
  error_file="$(mktemp "${destination}.error.XXXXXX")" || {
    rm -f -- "$partial_file"
    return 1
  }
  delay=$(( max_delay < 5 ? max_delay : 5 ))
  for (( attempt = 1; attempt <= max_attempts; attempt++ )); do
    : > "$partial_file"
    : > "$error_file"
    api_status=0
    if gh api "$@" > "$partial_file" 2> "$error_file"; then
      if [[ -s "$error_file" ]]; then
        cat "$error_file" >&2
      fi
      mv -f -- "$partial_file" "$destination"
      rm -f -- "$error_file"
      return 0
    else
      api_status=$?
    fi
    diagnostic="$(<"$error_file")"

    if ! grep -Eqi \
        'API rate limit exceeded|secondary rate limit|HTTP (408|429|5[0-9][0-9])|connection (reset|refused)|timed out|timeout|temporary failure|TLS handshake|unexpected EOF' \
        <<< "$diagnostic" || (( attempt == max_attempts )); then
      printf '%s\n' "$diagnostic" >&2
      rm -f -- "$partial_file" "$error_file"
      return "$api_status"
    fi

    local skew=0
    if [[ "${GITHUB_RUN_ID:-}" =~ ^[1-9][0-9]*$ ]]; then
      skew=$(( (GITHUB_RUN_ID + attempt) % 5 ))
    fi
    printf 'GitHub API attempt %s/%s failed transiently; retrying in %ss.\n' \
      "$attempt" "$max_attempts" "$((delay + skew))" >&2
    sleep "$((delay + skew))"
    delay=$(( delay < max_delay ? delay * 2 : max_delay ))
    if (( delay > max_delay )); then
      delay="$max_delay"
    fi
  done
}
