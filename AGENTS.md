## KisaAgent APK publication

When producing a signed KisaAgent APK for delivery, publish to the multikyd/kisa_agent repository on main. Advance the release version above the published version. Verify the APK uses the same signing certificate as the previous release. Commit kisa_agent.apk and kisa_agent_latest.json together with the matching version, SHA-256, and release notes, then push and verify origin/main. Keep signing files, local SDK settings, and unrelated local changes out of commits. Local debug or compile checks do not trigger publication.
