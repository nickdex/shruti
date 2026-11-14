import { registerPlugin } from "@capacitor/core";
import type { ShrutiCapturePlugin } from "./definitions";

export const ShrutiCapture = registerPlugin<ShrutiCapturePlugin>("ShrutiCapture");

export * from "./definitions";

