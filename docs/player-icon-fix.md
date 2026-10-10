# Player icon lifecycle fix (version 55)

Changing activePackage must refresh AppLogoStrategy independently of providerLogo. Both players can have a null or identical provider logo, so updating the style before setting the new package previously left the old icon (or a null image) in place.

AppLogoStrategy now rechecks the package on attach even if an image is still present. ProviderStrategy now retries on attach even after an earlier load failed and isEffective is false. Geometry, translation selection, default hidden views and API 102 are unchanged.

Four lifecycle regression tests passed: package switch with null provider logos, duplicate package, reattach with an existing application icon, and retry after a failed provider-logo load. Tests use mocked views to verify refresh calls; this environment has no connected Xiaomi device.
