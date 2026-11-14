import { execFileSync } from "node:child_process";
import { resolve } from "node:path";

const root = process.cwd();
const gradle = resolve(root, "android", process.platform === "win32" ? "gradlew.bat" : "gradlew");

execFileSync(process.execPath, [resolve(root, "scripts/android-device-doctor.mjs")], {
  cwd: root,
  stdio: "inherit",
});
execFileSync(gradle, ["connectedDebugAndroidTest"], {
  cwd: resolve(root, "android"),
  stdio: "inherit",
});
