# F-Droid release checklist

This source tree includes Fastlane metadata under
`fastlane/metadata/android/en-US/`, which F-Droid can use for the listing.

Before submitting to the main F-Droid repository, the project owner must:

1. Publish this source tree in a public Git repository.
2. Create a signed, tagged release and record its immutable commit hash.
3. Submit an fdroiddata metadata merge request for package
   `com.niallbunting.assistantvoicerecorder`. The metadata needs the chosen
   license, public repository URL, release commit hash, version name, and
   version code.
4. Add at least one phone screenshot under the Fastlane metadata directory.
   The 512px PNG icon is already present. A screenshot is still needed for
   the app to appear in F-Droid's Latest tab.

## Current build facts

- Package: `com.niallbunting.assistantvoicerecorder`
- Version: `1.0` (version code `1`)
- License: `GPL-3.0-or-later`
- Build: `./gradlew :app:assembleRelease`
- No proprietary SDK, analytics SDK, advertising SDK, or Firebase dependency is
  included. The optional network upload targets only the URL entered by the
  user.
