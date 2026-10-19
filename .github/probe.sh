#!/usr/bin/env bash
# Temporary: what NSE's SME Emerge index file holds. Remove before merging.
set -u
for u in https://archives.nseindia.com/content/indices/ind_niftysmeemerge_list.csv https://www.niftyindices.com/IndexConstituent/ind_niftysmeemerge_list.csv https://www.niftyindices.com/IndexConstituent/ind_niftyemerge_list.csv; do
  echo "== $u"
  curl -sS -m 30 -A "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36" -H "Referer: https://www.nseindia.com/" -o /tmp/p.bin -w "%{http_code} %{size_download} %{content_type}\n" "$u"
  head -c 600 /tmp/p.bin; echo; wc -l < /tmp/p.bin
done
