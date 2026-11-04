# 2026-11-04 — 002 — PhotoXT and PhotoXTPro editions

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop
- **Goal:** Make the session-001 spec the PhotoXTPro edition, and write a PhotoXT edition without image, video and media boards.

## Done
- Copied the full spec to [photoxtpro-build-prompt.md](../photoxtpro-build-prompt.md) and renamed the product to PhotoXTPro in it (scheme `photoxtpro://`, placeholder ID `com.photoxt.app.pro`). Appendix B, the original prompt, stays verbatim.
  - Added an Editions note, and an admin review queue for reports.
- Rewrote [photoxt-build-prompt.md](../photoxt-build-prompt.md) as the PhotoXT edition:
  - removed boards from the header, §1, §3, §4.1, §4.8, §4.15, §5.2, §5.3, §5.7, Phase 12 and Phase 15;
  - replaced §4.16 with public-album publishing, safety and profile;
  - listed boards under §8 (not in this edition), and updated §9, Appendix A and Appendix B.
- Kept the section and phase numbers identical in both files. Checked both for broken § and Phase references and malformed tables: none found.
- Updated README.md, AGENTS.md and memory.md. Refreshed both specs in the Claude project knowledge.

## Next
- Confirm or reject the recommended setup (one codebase, two product flavors) before Phase 0.
- Then start Phase 0 of PhotoXT.
