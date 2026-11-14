import { execFileSync, spawnSync } from "node:child_process";
import { homedir } from "node:os";
import { join, resolve } from "node:path";
const root = process.cwd();
const npm = process.platform === "win32" ? "npm.cmd" : "npm";
const gradle = resolve(root, "android", process.platform === "win32" ? "gradlew.bat" : "gradlew");
const sdkRoot = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || join(homedir(), "Library/Android/sdk");
const adb = join(sdkRoot, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
const apk = resolve(root, "android/app/build/outputs/apk/debug/app-debug.apk");

function run(executable, args, options = {}) {
  execFileSync(executable, args, { cwd: root, stdio: "inherit", ...options });
}

run(process.execPath, [resolve(root, "scripts/android-device-doctor.mjs")]);
run(npm, ["run", "cap:sync"]);
run(gradle, [":app:assembleDebug"], { cwd: resolve(root, "android") });

const devices = execFileSync(adb, ["devices"], { encoding: "utf8" })
  .split(/\r?\n/)
  .slice(1)
  .map((line) => line.trim().split(/\s+/))
  .filter(([serial, state]) => serial && state);

let failed = false;
let installed = 0;
for (const [serial, state] of devices) {
  if (state !== "device") {
    console.error(`Skipping ${serial}: ${state}`);
    failed = true;
    continue;
  }
  console.log(`Installing on ${serial}...`);
  const result = spawnSync(adb, ["-s", serial, "install", "-r", apk], { stdio: "inherit" });
  if (result.error || result.status !== 0) {
    console.error(`Installation failed on ${serial}${result.error ? `: ${result.error.message}` : ""}`);
    failed = true;
  } else {
    installed++;
  }
}

if (installed === 0) {
  console.error("No authorized Android devices were installed.");
  failed = true;
}
if (failed) process.exitCode = 1;
