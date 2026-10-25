#!/bin/sh
# Downloads a Vosk model and unpacks it into model/<lang>/assets/model, which the build flavour of that
# language packs into the APK's assets. Each zip is checked against the md5 that
# https://alphacephei.com/vosk/models/model-list.json publishes. Needs curl and unzip (Git Bash or WSL on
# Windows).
#
# Usage: ./fetch-model.sh ru|en|all      A model already in place is left alone; a renamed one is replaced.
set -eu
cd "$(dirname "$0")"

fetch() {
    lang=$1; name=$2; md5=$3
    dir="model/$lang/assets/model"
    if [ -f "$dir/am/final.mdl" ] && [ "$(cat "model/$lang/name" 2>/dev/null)" = "$name" ]; then
        echo "$dir is already in place ($name)"
        return
    fi
    mkdir -p "model/$lang/assets"
    if [ ! -f "model/$name.zip" ]; then
        curl -fL --retry 3 -o "model/$name.zip.part" "https://alphacephei.com/vosk/models/$name.zip"
        mv "model/$name.zip.part" "model/$name.zip"
    fi
    if command -v md5sum >/dev/null 2>&1; then
        actual=$(md5sum "model/$name.zip" | cut -d' ' -f1)
    else
        actual=$(md5 -q "model/$name.zip")     # macOS
    fi
    if [ "$actual" != "$md5" ]; then
        rm -f "model/$name.zip"
        echo "model/$name.zip: md5 $actual, expected $md5; deleted it, run again" >&2
        exit 1
    fi
    rm -rf "model/$name" "$dir" "model/$lang/name"
    unzip -q -d model "model/$name.zip"
    mv "model/$name" "$dir"
    echo "$name" > "model/$lang/name"
    echo "$dir ready ($name)"
}

# Word-split on purpose: language, model name, md5.
RU='ru vosk-model-small-ru-0.22 d1759dc83eb8fd87850129afbd9f4b7b'
EN='en vosk-model-small-en-us-0.15 09ab50ccd62b674cbaa231b825f9c1cb'

[ $# -gt 0 ] || { echo "usage: $0 ru|en|all" >&2; exit 2; }
for lang in "$@"; do
    case "$lang" in
        ru)  fetch $RU ;;
        en)  fetch $EN ;;
        all) fetch $RU; fetch $EN ;;
        *)   echo "usage: $0 ru|en|all" >&2; exit 2 ;;
    esac
done
