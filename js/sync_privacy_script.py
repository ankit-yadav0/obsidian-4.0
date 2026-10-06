#!/usr/bin/env python3
"""Regenerates PrivacyScripts.kt from js/privacy_hardening.js.

Workflow when you change the injected script:
    node js/test_hardening.js          # must stay green
    python3 js/sync_privacy_script.py  # copies the script into the Kotlin constant
"""
import pathlib

root = pathlib.Path(__file__).resolve().parent.parent
js = (root / "js" / "privacy_hardening.js").read_text()
assert "$" not in js, "a dollar sign would become a Kotlin string template"
assert '"""' not in js, "triple quotes would end the Kotlin raw string"

kotlin = '''package com.example.weblite.webview

/**
 * JavaScript injected into every page (and every frame) before the page's own scripts run.
 *
 * GENERATED from js/privacy_hardening.js by js/sync_privacy_script.py - edit the .js file, run
 * `node js/test_hardening.js`, then re-run the sync script. The Node tests exercise every behaviour
 * below against a mock browser.
 *
 * Contains no dollar signs on purpose: this is a Kotlin raw string, where "$name" would be a template.
 */
object PrivacyScripts {
    const val DOCUMENT_START: String = """
''' + js + '''"""
}
'''
out = root / "app/src/main/java/com/example/weblite/webview/PrivacyScripts.kt"
out.write_text(kotlin)
print("wrote", out.relative_to(root))
