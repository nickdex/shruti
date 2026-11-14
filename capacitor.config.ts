import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "dev.shruti.capture",
  appName: "Shruti",
  webDir: "public",
  server: {
    androidScheme: "https"
  }
};

export default config;
