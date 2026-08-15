#!/usr/bin/env bash

# Bounded retry wrappers for GitHub CLI calls in protected workflows. Successful textual responses
# are the only stdout; diagnostics stay on stderr so callers may safely use command substitution or
# pipelines. Authentication/provenance validation remains the caller's job.
_github_retry_bounds_valid() {
  local max_attempts="$1" max_delay="$2" max_wait="$3"
  [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]] \
    && (( max_attempts <= 30 )) \
    && [[ "$max_delay" =~ ^[1-9][0-9]*$ ]] \
    && (( max_delay <= 300 )) \
    && [[ "$max_wait" =~ ^[1-9][0-9]*$ ]] \
    && (( max_wait <= 7200 ))
}

_github_retry_wait() {
  local diagnostic="$1" attempt="$2" max_attempts="$3" delay="$4" max_delay="$5"
  local max_wait="$6"
  local reset_at now skew wait_seconds
  skew=0
  if [[ "${GITHUB_RUN_ID:-}" =~ ^[1-9][0-9]*$ ]]; then
    skew=$(( (GITHUB_RUN_ID + attempt) % 5 ))
  fi

  if grep -Fqi 'API rate limit exceeded' <<< "$diagnostic"; then
    # GET /rate_limit does not consume primary quota. Use it once to recover the reset timestamp
    # that `gh` omits from its error text, then make no further primary request before that window.
    reset_at="$(gh api rate_limit --jq .resources.core.reset 2>/dev/null || true)"
    if [[ "$reset_at" =~ ^[1-9][0-9]*$ ]]; then
      now="$(date +%s)"
      wait_seconds=$(( reset_at - now + 2 + skew ))
      (( wait_seconds > 0 )) || wait_seconds=1
      if (( wait_seconds > max_wait )); then
        printf 'GitHub primary rate limit resets in %ss, beyond this caller maximum of %ss.\n' \
          "$wait_seconds" "$max_wait" >&2
        return 1
      fi
      printf 'GitHub primary rate limit exhausted; waiting %ss for its declared reset.\n' \
        "$wait_seconds" >&2
      sleep "$wait_seconds"
      return 0
    fi
  fi

  wait_seconds="$delay"
  if grep -Eqi 'secondary rate limit|abuse detection|HTTP 429' <<< "$diagnostic"; then
    # GitHub requires at least a minute when a secondary response has no Retry-After header.
    (( wait_seconds >= 60 )) || wait_seconds=60
    (( wait_seconds <= max_delay )) || wait_seconds="$max_delay"
  fi
  wait_seconds=$(( wait_seconds + skew ))
  if (( wait_seconds > max_wait )); then
    printf 'GitHub API retry delay %ss exceeds this caller maximum of %ss.\n' \
      "$wait_seconds" "$max_wait" >&2
    return 1
  fi
  printf 'GitHub API attempt %s/%s failed transiently; retrying in %ss.\n' \
    "$attempt" "$max_attempts" "$wait_seconds" >&2
  sleep "$wait_seconds"
}

github_cli_retry() {
  if (( $# == 0 )); then
    printf 'github_cli_retry requires a command\n' >&2
    return 2
  fi

  local max_attempts="${GITHUB_API_RETRY_ATTEMPTS:-4}"
  local max_delay="${GITHUB_API_RETRY_MAX_DELAY_SECONDS:-60}"
  local max_wait="${GITHUB_API_RETRY_MAX_WAIT_SECONDS:-300}"
  if ! _github_retry_bounds_valid "$max_attempts" "$max_delay" "$max_wait"; then
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

    if ! _github_retry_wait \
        "$output" "$attempt" "$max_attempts" "$delay" "$max_delay" "$max_wait"; then
      printf '%s\n' "$output" >&2
      return "$api_status"
    fi
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
  local max_delay="${GITHUB_API_RETRY_MAX_DELAY_SECONDS:-60}"
  local max_wait="${GITHUB_API_RETRY_MAX_WAIT_SECONDS:-300}"
  if ! _github_retry_bounds_valid "$max_attempts" "$max_delay" "$max_wait"; then
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

    if ! _github_retry_wait \
        "$diagnostic" "$attempt" "$max_attempts" "$delay" "$max_delay" "$max_wait"; then
      printf '%s\n' "$diagnostic" >&2
      rm -f -- "$partial_file" "$error_file"
      return "$api_status"
    fi
    delay=$(( delay < max_delay ? delay * 2 : max_delay ))
    if (( delay > max_delay )); then
      delay="$max_delay"
    fi
  done
}
