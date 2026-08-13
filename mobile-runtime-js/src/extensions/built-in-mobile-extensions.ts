import {
  MobileExtensionHost,
  type MobileExtensionDescriptor,
} from "./mobile-extension-host.js";

/**
 * PXP-1 intentionally keeps the production registry empty. Connector becomes
 * the first built-in extension only after its deterministic PXP-3 gate passes.
 */
export const BUILT_IN_MOBILE_EXTENSIONS: readonly MobileExtensionDescriptor[] = [];

export function createBuiltInMobileExtensionHost(
  taskExtensions: readonly MobileExtensionDescriptor[] = [],
): MobileExtensionHost {
  return new MobileExtensionHost([
    ...BUILT_IN_MOBILE_EXTENSIONS,
    ...taskExtensions,
  ]);
}
