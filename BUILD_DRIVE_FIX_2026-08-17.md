# Short build-drive fix (2026-08-17)

`build_apk.bat` no longer assumes that `Z:` is available. Interrupted builds can leave a SUBST mapping behind, which caused:

```text
Drive already SUBSTed
[ERROR] Could not create short build drive Z:.
```

The script now tries `Z:` down through `G:` and uses the first drive letter Windows accepts. Existing physical, network, or SUBST drives are not removed or overwritten. Only the mapping created by the current build is removed on normal exit.
