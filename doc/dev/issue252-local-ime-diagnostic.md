# #252 local Samsung keyboard structure diagnostic

Companion change for the next normal update, not a keyboard fix or standalone
release. The previous separate debug candidate prepared for this diagnosis was
blocked at ordinary installation by Play Protect. Existing v0.1.127 remains
the reference for the blocked visual sequence.

In ClosePaw, approve the existing Android screen share. With other control
sessions ended, open Settings → System & Debug → Samsung keyboard structure.
Arm one attempt, switch to Chrome, and focus an empty editor with Samsung
keyboard without typing or submitting. Return to the diagnostic screen to read
the in-memory result, then stop the existing share. Setup stays unchanged and
the diagnostic does not stop, resume, or originate remote sessions.
The blank editor is bound under the existing Chrome and Samsung IME read rules
before the first native screenshot. A subsequent focus, content, consent or
session change refuses capture. After the readiness stage, an independent
15-second capture limit releases the shared screen lease even if Android never
calls back. Capture errors stay local and do not enter the remote diagnostics.


This is a local UI only. No new Android permission or remote action, no
automatic export, and no copy to a clipboard without a local user action.
The report uses run-local aliases and rectangles. It includes the common
ancestor, independently observed physical rows and certified coverage in the
same local frame. It includes no native node identifiers, text, resource
names, image IDs, image bytes or hashes. The JSON is not written to a file.

A common root, ambiguous wrappers, shared or overlapping physical rows and
unavailable pixels are not positive strip evidence. Local certified pixels
are only evidence for the captured local frame. They cannot establish what a
different remote-control overlay or later frame masks. The geometry report
does not authorize any touch and does not satisfy the #252 acceptance gate.
