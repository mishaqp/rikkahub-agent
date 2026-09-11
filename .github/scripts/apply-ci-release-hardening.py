from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}\n--- OLD ---\n{old}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"updated {path}")


replace_once(
    "gradle.properties",
    "org.gradle.configuration-cache=true\n",
    "org.gradle.configuration-cache=true\norg.gradle.caching=true\n",
)

ci = ".github/workflows/ci.yml"
replace_once(
    ci,
    '''      - name: Release lint\n        timeout-minutes: 40\n        run: >-\n          ./gradlew :app:lintRelease -Parm64Only=true\n          --no-daemon --no-parallel --max-workers=1 --console=plain\n          -Dorg.gradle.jvmargs="-Xmx6g -Dfile.encoding=UTF-8"\n      - name: Build arm64 release APK\n        timeout-minutes: 30\n        run: >-\n          ./gradlew :app:assembleRelease -Parm64Only=true\n          --no-daemon --max-workers=2\n          -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8"\n      - name: Build arm64 release AAB\n        timeout-minutes: 20\n        run: >-\n          ./gradlew :app:bundleRelease -Parm64Only=true\n          --no-daemon --max-workers=2\n          -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8"\n''',
    '''      - name: Lint and build arm64 release APK + AAB\n        timeout-minutes: 75\n        run: >-\n          ./gradlew\n          :app:lintRelease\n          :app:assembleRelease\n          :app:bundleRelease\n          -Parm64Only=true\n          --no-daemon --no-parallel --max-workers=1 --console=plain\n          -Dorg.gradle.jvmargs="-Xmx6g -Dfile.encoding=UTF-8"\n''',
)
replace_once(
    ci,
    '''          grep -q "versionName='2.5.1-agent.1'" /tmp/badging.txt || { echo "::error::unexpected versionName"; exit 1; }\n''',
    '''          EXPECTED_NAME=$(sed -n 's/.*versionName = "\\([^"]*\\)".*/\\1/p' app/build.gradle.kts | head -1)\n          [ -n "$EXPECTED_NAME" ] || { echo "::error::could not read versionName from app/build.gradle.kts"; exit 1; }\n          echo "versionName: expected=$EXPECTED_NAME"\n          grep -Fq "versionName='$EXPECTED_NAME'" /tmp/badging.txt || { echo "::error::unexpected versionName"; exit 1; }\n''',
)

release = ".github/workflows/release.yml"
compute = '''      - name: Compute VERSION_CODE\n        run: |\n          CODE="$(bash .github/scripts/version-code.sh)"\n          echo "VERSION_CODE=$CODE" >> "$GITHUB_ENV"\n          echo "VERSION_CODE=$CODE" >> "$GITHUB_STEP_SUMMARY"\n'''
replace_once(
    release,
    compute,
    compute + '''      - name: Read release version\n        run: |\n          python3 - <<'PY'\n          import os, pathlib, re\n          source = pathlib.Path("app/build.gradle.kts").read_text()\n          match = re.search(r'versionName = "([^"]+)"', source)\n          if not match:\n              raise SystemExit("versionName not found in app/build.gradle.kts")\n          with open(os.environ["GITHUB_ENV"], "a") as f:\n              f.write("RELEASE_TAG=v" + match.group(1) + "\\n")\n          PY\n      - name: Refuse an existing release tag\n        shell: bash\n        run: |\n          set -euo pipefail\n          if git rev-parse -q --verify "refs/tags/${RELEASE_TAG}" >/dev/null; then\n            echo "::error::Release tag ${RELEASE_TAG} already exists. Bump versionName before publishing."\n            exit 1\n          fi\n      - name: Verify versionCode is newer than latest published APK\n        env:\n          GH_TOKEN: ${{ github.token }}\n        shell: bash\n        run: |\n          set -euo pipefail\n          if ! LATEST_TAG=$(gh api "repos/${GITHUB_REPOSITORY}/releases/latest" --jq '.tag_name' 2>/dev/null); then\n            echo "No previous published release; skipping monotonic versionCode comparison."\n            exit 0\n          fi\n          mkdir -p "$RUNNER_TEMP/previous-release"\n          gh release download "$LATEST_TAG" --pattern '*.apk' --dir "$RUNNER_TEMP/previous-release"\n          shopt -s nullglob\n          APKS=("$RUNNER_TEMP/previous-release"/*.apk)\n          if [ ${#APKS[@]} -eq 0 ]; then\n            echo "::error::Latest release $LATEST_TAG has no APK asset; cannot verify versionCode monotonicity."\n            exit 1\n          fi\n          AAPT2=$(find "$ANDROID_HOME/build-tools" -type f -name aapt2 | sort -V | tail -1)\n          [ -n "$AAPT2" ] || { echo "::error::aapt2 not found"; exit 1; }\n          PREV_MAX=0\n          for PREV_APK in "${APKS[@]}"; do\n            PREV_CODE=$("$AAPT2" dump badging "$PREV_APK" | sed -n "s/.*versionCode='\\([0-9]*\\)'.*/\\1/p" | head -1)\n            [ -n "$PREV_CODE" ] || { echo "::error::Could not read versionCode from $PREV_APK"; exit 1; }\n            if [ "$PREV_CODE" -gt "$PREV_MAX" ]; then PREV_MAX="$PREV_CODE"; fi\n          done\n          echo "versionCode: new=$VERSION_CODE latest_published=$PREV_MAX ($LATEST_TAG)"\n          if [ "$VERSION_CODE" -le "$PREV_MAX" ]; then\n            echo "::error::New versionCode $VERSION_CODE must be greater than latest published versionCode $PREV_MAX."\n            exit 1\n          fi\n''',
)
replace_once(
    release,
    '''      - name: Install Vulkan build dependencies\n        run: |\n          sudo apt-get update\n          sudo apt-get install -y --no-install-recommends libvulkan-dev glslc spirv-headers\n''',
    '',
)
replace_once(
    release,
    '''      - name: Release lint (fixed memory)\n        timeout-minutes: 30\n        run: ./gradlew :app:lintRelease -Parm64Only=true --no-daemon --no-parallel --max-workers=1 --console=plain --info --stacktrace -Dorg.gradle.jvmargs="-Xmx6g -Dfile.encoding=UTF-8"\n      - name: Build arm64 release APK\n        timeout-minutes: 25\n        run: ./gradlew :app:assembleRelease -Parm64Only=true --no-daemon --no-parallel --max-workers=2 --console=plain --info --stacktrace -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8"\n      - name: Build arm64 release AAB\n        timeout-minutes: 25\n        run: ./gradlew :app:bundleRelease -Parm64Only=true --no-daemon --no-parallel --max-workers=2 --console=plain --info --stacktrace -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8"\n''',
    '''      - name: Lint and build arm64 release APK + AAB\n        timeout-minutes: 70\n        run: ./gradlew :app:lintRelease :app:assembleRelease :app:bundleRelease -Parm64Only=true --no-daemon --no-parallel --max-workers=1 --console=plain --info --stacktrace -Dorg.gradle.jvmargs="-Xmx6g -Dfile.encoding=UTF-8"\n''',
)
replace_once(
    release,
    '''      - name: Read release version\n        run: |\n          python3 - <<'PY'\n          import os, pathlib, re\n          source = pathlib.Path("app/build.gradle.kts").read_text()\n          version = re.search(r'versionName = "([^"]+)"', source).group(1)\n          with open(os.environ["GITHUB_ENV"], "a") as f:\n              f.write("RELEASE_TAG=v" + version + "\\n")\n          PY\n''',
    '',
)
replace_once(
    release,
    '''          generate_release_notes: true\n          files: |\n''',
    '''          generate_release_notes: true\n          fail_on_unmatched_files: true\n          files: |\n''',
)

print("CI/release hardening applied")
