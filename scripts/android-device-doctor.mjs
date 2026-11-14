import { existsSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

const issues = [];
const notes = [];

function check(condition, message) {
  if (!condition) issues.push(message);
}

function commandOutput(executable, args) {
  const result = spawnSync(executable, args, { encoding: "utf8" });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || `exit ${result.status}`).trim());
  }
  return `${result.stdout || ""}\n${result.stderr || ""}`;
}

const javaHome = process.env.JAVA_HOME;
check(Boolean(javaHome), "Set JAVA_HOME to a JDK 21 installation before building.");

if (javaHome) {
  const java = join(javaHome, "bin", "java");
  check(existsSync(java), `JAVA_HOME does not contain bin/java: ${javaHome}`);
  if (existsSync(java)) {
    try {
      const version = commandOutput(java, ["-version"]);
      check(/version \"21(?:[.\"]|$)/.test(version), "Shruti's Capacitor Android host requires JDK 21.");
    } catch (error) {
      issues.push(`Could not run JAVA_HOME/bin/java: ${error.message}`);
    }
  }
}

const sdkRoot = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || join(homedir(), "Library/Android/sdk");
const platform36 = join(sdkRoot, "platforms", "android-36");
const buildTools = join(sdkRoot, "build-tools");
const adb = join(sdkRoot, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");

check(existsSync(platform36), `Install Android SDK Platform 36 under ${sdkRoot}.`);
check(existsSync(buildTools) && readdirSync(buildTools).some((name) => name.startsWith("36.")),
  `Install Android SDK Build-Tools 36.x under ${sdkRoot}.`);
check(existsSync(adb), `Install Android platform-tools or set ANDROID_SDK_ROOT (expected ${adb}).`);

if (existsSync(adb)) {
  try {
    const devices = commandOutput(adb, ["devices", "-l"])
      .split(/\r?\n/)
      .filter((line) => /\sdevice(?:\s|$)/.test(line));
    check(devices.length > 0, "Connect and authorize at least one Android phone, then run adb devices -l.");
    if (devices.length > 0) notes.push(`Authorized Android device${devices.length === 1 ? "" : "s"}: ${devices.length}.`);
  } catch (error) {
    issues.push(`Could not query ADB devices: ${error.message}`);
  }
}

if (issues.length) {
  console.error("Android device check failed:\n");
  for (const issue of issues) console.error(`- ${issue}`);
  process.exitCode = 1;
} else {
  console.log("Android device check passed.");
  for (const note of notes) console.log(note);
}
