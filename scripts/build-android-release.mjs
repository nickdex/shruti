import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve } from "node:path";

const root = process.cwd();
const androidRoot = resolve(root, "android");
const gradle = resolve(androidRoot, process.platform === "win32" ? "gradlew.bat" : "gradlew");
const sdkRoot = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || join(homedir(), "Library/Android/sdk");
const { version } = JSON.parse(readFileSync(resolve(root, "package.json"), "utf8"));
const signedApk = resolve(androidRoot, "app/build/outputs/apk/release/app-release.apk");
const unsignedApk = resolve(androidRoot, "app/build/outputs/apk/release/app-release-unsigned.apk");
const output = resolve(root, "dist", `shruti-${version}.apk`);

if (!existsSync(resolve(androidRoot, "keystore.properties"))) {
  console.error("android/keystore.properties is missing; release APKs must be signed for Obtainium. See docs/android-release.md.");
  process.exit(1);
}

const buildTools = join(sdkRoot, "build-tools");
const newestBuildTools = readdirSync(buildTools)
  .filter((name) => existsSync(join(buildTools, name, "apksigner")))
  .sort((a, b) => a.localeCompare(b, "en", { numeric: true }))
  .pop();
if (!newestBuildTools) {
  console.error(`No apksigner found under ${buildTools}.`);
  process.exit(1);
}
const apksigner = join(buildTools, newestBuildTools, "apksigner");
const aapt2 = join(buildTools, newestBuildTools, "aapt2");

execFileSync(gradle, [":app:assembleRelease"], { cwd: androidRoot, stdio: "inherit" });

const apk = existsSync(signedApk) ? signedApk : unsignedApk;
if (!existsSync(signedApk)) {
  console.error("Gradle produced an unsigned APK; check the signingConfig in android/app/build.gradle.");
  process.exit(1);
}

const badging = execFileSync(aapt2, ["dump", "badging", apk], { encoding: "utf8" });
const declared = /versionName='([^']*)'/.exec(badging);
if (!declared || declared[1] !== version) {
  console.error(`APK versionName ${declared?.[1]} does not match package.json ${version}.`);
  process.exit(1);
}

execFileSync(apksigner, ["verify", "--print-certs", apk], { stdio: "inherit" });

mkdirSync(resolve(root, "dist"), { recursive: true });
copyFileSync(apk, output);
const sha256 = createHash("sha256").update(readFileSync(output)).digest("hex");

console.log(`\nAPK:    ${output}`);
console.log(`SHA256: ${sha256}`);
console.log(`\nPublish with: gh release create v${version} ${output} --title "v${version}" --notes-file <notes>`);
