package com.photoxt.core.render

/**
 * The editor's one shader (spec §4.10): it renders a whole recipe for each output pixel, so the
 * preview and every tile of an export run the same code. Everything it reads is a function of
 * the output pixel's place in the source (fractions of the raster), so tiles meet seamlessly
 * and a downscaled export matches the preview: neighbourhood effects (clarity, dehaze, blurs,
 * redaction) read fixed low-resolution guides of the whole photo, not the tile.
 */
internal object EditShader {
    const val MAX_MASKS = 4
    const val MAX_EYES = 8
    const val MAX_FACES = 8
    const val MAX_REDACTIONS = 32

    val VERTEX = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        uniform vec2 uTileOrigin;
        uniform vec2 uTileSize;
        out vec2 vPos;
        void main() {
            // Row 0 of the framebuffer is the tile's top row, so glReadPixels reads top down.
            vPos = uTileOrigin + aPos * uTileSize;
            gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision highp float;
        precision highp int;
        precision mediump sampler3D;

        in vec2 vPos;
        out vec4 outColor;

        uniform mat3 uMap;
        uniform vec4 uRegion;
        uniform vec2 uRaster;
        uniform sampler2D uSrc;
        uniform sampler2D uGuide;
        uniform sampler2D uGuideSoft;
        uniform sampler2D uGuideBlur;
        uniform vec2 uSrcStep;
        uniform vec4 uContent;
        uniform vec4 uBorderColor;
        uniform float uRadius;

        uniform float uExposure;
        uniform float uBrightness;
        uniform float uContrast;
        uniform float uHighlights;
        uniform float uShadows;
        uniform float uWhites;
        uniform float uBlacks;
        uniform vec3 uWb;
        uniform float uSaturation;
        uniform float uVibrance;
        uniform float uHue;
        uniform int uHslOn;
        uniform vec3 uHsl[8];
        uniform int uCurvesOn;
        uniform sampler2D uCurves;
        uniform sampler3D uLut;
        uniform float uLutMix;
        uniform float uLutSize;
        uniform float uSharpen;
        uniform float uClarity;
        uniform float uDehaze;
        uniform vec3 uHaze;
        uniform float uVignette;
        uniform float uGrain;
        uniform float uFade;

        uniform int uMaskCount;
        uniform ivec2 uMaskKind[$MAX_MASKS];
        uniform vec4 uMaskGeom[$MAX_MASKS];
        uniform vec4 uMaskExtra[$MAX_MASKS];
        uniform vec4 uMaskA[$MAX_MASKS];
        uniform vec4 uMaskB[$MAX_MASKS];
        uniform sampler2D uBrush;
        uniform sampler2D uSubject;

        uniform int uEyeCount;
        uniform vec3 uEyes[$MAX_EYES];
        uniform float uSmooth;
        uniform int uFaceCount;
        uniform vec4 uFaces[$MAX_FACES];
        uniform float uBgBlur;

        uniform int uRedactCount;
        uniform vec4 uRedactRect[$MAX_REDACTIONS];
        uniform ivec2 uRedactStyle[$MAX_REDACTIONS];
        uniform vec4 uRedactColor[$MAX_REDACTIONS];
        uniform float uCell;

        uniform int uBgMode;
        uniform vec4 uBgColor;
        uniform sampler2D uBgImage;
        uniform vec4 uBgImageMap;

        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
        const float HSL_CENTRES[9] = float[9](0.0, 30.0, 60.0, 120.0, 180.0, 225.0, 270.0, 315.0, 360.0);

        float luma(vec3 c) { return dot(c, LUMA); }

        vec3 toLinear(vec3 c) {
            return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
        }

        vec3 toSrgb(vec3 c) {
            c = max(c, 0.0);
            return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
        }

        vec3 rgbToHsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }

        vec3 hsvToRgb(vec3 c) {
            vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
            vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }

        // Moves luminance from l to lp, by ratio where there is colour to scale, else by adding.
        vec3 withLuma(vec3 c, float l, float lp) {
            vec3 scaled = c * (lp / max(l, 1.0e-4));
            vec3 added = c + (lp - l);
            return mix(added, scaled, smoothstep(0.02, 0.2, l));
        }

        float tone(float l, float highlights, float shadows) {
            l += shadows * 1.5 * l * (1.0 - l) * (1.0 - l);
            l += highlights * 1.5 * l * l * (1.0 - l);
            return l;
        }

        vec3 contrasted(vec3 c, float amount) {
            if (amount >= 0.0) return mix(c, c * c * (3.0 - 2.0 * c), amount);
            return mix(vec3(0.5), c, 1.0 + amount);
        }

        // Source fraction -> pixel position in the raster.
        vec2 px(vec2 s) { return s * uRaster; }

        float shortEdge() { return min(uRaster.x, uRaster.y); }

        float maskWeight(int i, vec2 s, float detail) {
            int kind = uMaskKind[i].x;
            float w = 0.0;
            if (kind == 0) {
                w = texture(uBrush, s)[int(uMaskExtra[i].z)];
            } else if (kind == 1) {
                vec2 a = px(uMaskGeom[i].xy);
                vec2 b = px(uMaskGeom[i].zw);
                vec2 ab = b - a;
                float t = dot(px(s) - a, ab) / max(dot(ab, ab), 1.0);
                w = 1.0 - smoothstep(0.0, 1.0, t);
            } else if (kind == 2) {
                vec2 d = px(s) - px(uMaskGeom[i].xy);
                float ang = uMaskExtra[i].x;
                vec2 r = vec2(cos(ang) * d.x + sin(ang) * d.y, -sin(ang) * d.x + cos(ang) * d.y);
                vec2 radii = max(uMaskGeom[i].zw * shortEdge(), vec2(1.0));
                float dist = length(r / radii);
                float feather = clamp(uMaskExtra[i].y, 0.01, 1.0);
                w = 1.0 - smoothstep(1.0 - feather, 1.0, dist);
            } else if (kind == 3) {
                w = texture(uSubject, s).r;
            } else if (kind == 4) {
                w = 1.0 - texture(uSubject, s).r;
            }
            if (uMaskKind[i].y == 1) w = 1.0 - w;
            return clamp(w, 0.0, 1.0);
        }

        vec3 localAdjust(vec3 c, vec4 a, vec4 b, float detail) {
            vec3 lin = toLinear(c) * exp2(a.x);
            vec3 gains = vec3(1.0 + 0.3 * b.y, 1.0 - 0.2 * b.z, 1.0 - 0.3 * b.y);
            lin *= gains / dot(gains, LUMA);
            c = clamp(toSrgb(lin), 0.0, 1.0);
            float l = luma(c);
            float lp = tone(l, a.z, a.w) + b.w * detail * 1.5;
            c = clamp(withLuma(c, l, lp), 0.0, 1.0);
            c = clamp(contrasted(c, a.y * 0.6), 0.0, 1.0);
            float ln = luma(c);
            return clamp(mix(vec3(ln), c, 1.0 + b.x), 0.0, 1.0);
        }

        float hash(vec2 p) {
            p = fract(p * vec2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float valueNoise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float a = hash(i);
            float b = hash(i + vec2(1.0, 0.0));
            float c = hash(i + vec2(0.0, 1.0));
            float d = hash(i + vec2(1.0, 1.0));
            return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
        }

        // Which redaction covers source point s: its index, or -1.
        int redactionAt(vec2 s) {
            for (int i = 0; i < $MAX_REDACTIONS; i++) {
                if (i >= uRedactCount) break;
                vec4 r = uRedactRect[i];
                if (uRedactStyle[i].y == 1) {
                    vec2 centre = px((r.xy + r.zw) * 0.5);
                    vec2 radii = max(px(r.zw - r.xy) * 0.5, vec2(1.0));
                    if (length((px(s) - centre) / radii) <= 1.0) return i;
                } else if (s.x >= r.x && s.x <= r.z && s.y >= r.y && s.y <= r.w) {
                    return i;
                }
            }
            return -1;
        }

        void main() {
            // The frame around the picture.
            vec2 local = vPos - uContent.xy;
            vec2 uv = local / uContent.zw;
            if (uBorderColor.a > 0.0) {
                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
                    outColor = vec4(uBorderColor.rgb * uBorderColor.a, uBorderColor.a);
                    return;
                }
            }
            float roundEdge = 1.0;
            if (uRadius > 0.0) {
                vec2 half = uContent.zw * 0.5;
                vec2 q = abs(local - half) - (half - vec2(uRadius));
                float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
                roundEdge = 1.0 - smoothstep(-0.5, 0.5, dist);
            }

            vec3 m = uMap * vec3(vPos, 1.0);
            vec2 s = m.xy / m.z;
            vec2 t = (s - uRegion.xy) * uRegion.zw;

            int redact = uRedactCount > 0 ? redactionAt(s) : -1;
            int redactStyle = redact >= 0 ? uRedactStyle[redact].x : -1;
            vec3 c;
            vec4 texel = texture(uSrc, t);
            float alpha = 1.0;
            if (redactStyle == 1) {
                // Pixelate: every cell takes the guide's colour at its centre.
                float cell = max(uCell * shortEdge(), 1.0);
                vec2 centre = (floor(px(s) / cell) + 0.5) * cell / uRaster;
                c = texture(uGuide, centre).rgb;
            } else if (redactStyle == 0) {
                c = texture(uGuideBlur, s).rgb;
            } else {
                c = texel.a > 0.0 ? texel.rgb / texel.a : vec3(0.0);
            }
            bool detailOn = redact < 0;
            float srcLuma = luma(c);
            float detail = detailOn ? srcLuma - luma(texture(uGuideSoft, s).rgb) : 0.0;

            // Retouch on the photo itself: red-eye, skin, background blur.
            for (int i = 0; i < $MAX_EYES; i++) {
                if (i >= uEyeCount) break;
                float d = length(px(s) - px(uEyes[i].xy)) / max(uEyes[i].z * shortEdge(), 1.0);
                float red = c.r - max(c.g, c.b);
                float w = (1.0 - smoothstep(0.7, 1.0, d)) * smoothstep(0.04, 0.18, red);
                c.r = mix(c.r, (c.g + c.b) * 0.5, w);
            }
            if (uSmooth > 0.0 && detailOn) {
                float face = 0.0;
                for (int i = 0; i < $MAX_FACES; i++) {
                    if (i >= uFaceCount) break;
                    vec2 radii = max(uFaces[i].zw * shortEdge(), vec2(1.0));
                    float d = length((px(s) - px(uFaces[i].xy)) / radii);
                    face = max(face, 1.0 - smoothstep(0.75, 1.0, d));
                }
                vec3 soft = texture(uGuideSoft, s).rgb;
                float keep = 1.0 - smoothstep(0.05, 0.18, length(c - soft));
                c = mix(c, soft, uSmooth * face * keep * 0.85);
                detail *= 1.0 - uSmooth * face;
            }
            float subject = texture(uSubject, s).r;
            float bgBlur = max(uBgBlur, uBgMode == 3 ? 1.0 : 0.0);
            if (bgBlur > 0.0) {
                c = mix(c, texture(uGuideBlur, s).rgb, bgBlur * (1.0 - subject));
            }

            // Dehaze, from the blurred guide's dark channel.
            if (uDehaze != 0.0) {
                vec3 g = texture(uGuideBlur, s).rgb;
                float dark = min(g.r, min(g.g, g.b)) / max(max(uHaze.r, max(uHaze.g, uHaze.b)), 0.05);
                float tr = max(1.0 - 0.9 * dark, 0.15);
                vec3 clear = clamp((c - uHaze) / tr + uHaze, 0.0, 1.0);
                c = uDehaze > 0.0 ? mix(c, clear, uDehaze) : mix(c, mix(c, uHaze, 0.6), -uDehaze);
            }

            // White balance and exposure in linear light.
            vec3 lin = toLinear(c) * uWb * exp2(uExposure);
            c = clamp(toSrgb(lin), 0.0, 1.0);

            // Tone: whites and blacks set the ends, then shadows, highlights, brightness.
            float l = luma(c);
            float lo = -0.15 * uBlacks;
            float hi = 1.0 - 0.15 * uWhites;
            float lp = clamp((l - lo) / (hi - lo), 0.0, 1.5);
            lp = tone(clamp(lp, 0.0, 1.0), uHighlights, uShadows);
            lp = pow(clamp(lp, 0.0, 1.0), exp(-uBrightness * 0.7));
            lp += uClarity * detail * 1.5 * (1.0 - abs(lp * 2.0 - 1.0) * 0.5);
            c = clamp(withLuma(c, l, lp), 0.0, 1.0);
            c = clamp(contrasted(c, uContrast * 0.6), 0.0, 1.0);

            // Sharpen: the photo's own fine detail, from its neighbours in the tile.
            if (uSharpen > 0.0 && detailOn) {
                vec3 n = texture(uSrc, t + vec2(uSrcStep.x, 0.0)).rgb
                    + texture(uSrc, t - vec2(uSrcStep.x, 0.0)).rgb
                    + texture(uSrc, t + vec2(0.0, uSrcStep.y)).rgb
                    + texture(uSrc, t - vec2(0.0, uSrcStep.y)).rgb;
                float fine = luma(texel.rgb) - luma(n) * 0.25;
                c = clamp(c + fine * uSharpen * 2.0, 0.0, 1.0);
            }

            // Curves.
            if (uCurvesOn == 1) {
                vec3 k = c * (255.0 / 256.0) + 0.5 / 256.0;
                c = vec3(texture(uCurves, vec2(k.r, 0.5)).r, texture(uCurves, vec2(k.g, 0.5)).g,
                    texture(uCurves, vec2(k.b, 0.5)).b);
            }

            // Hue, HSL per colour, saturation and vibrance.
            if (uHslOn == 1 || uHue != 0.0) {
                vec3 hsv = rgbToHsv(c);
                float h = hsv.x * 360.0;
                float shift = uHue * 180.0;
                float satScale = 1.0;
                float lumShift = 0.0;
                if (uHslOn == 1) {
                    for (int i = 0; i < 8; i++) {
                        float a = HSL_CENTRES[i];
                        float b = HSL_CENTRES[i + 1];
                        if (h >= a && h < b) {
                            float f = (h - a) / (b - a);
                            int j = i == 7 ? 0 : i + 1;
                            vec3 band = uHsl[i] * (1.0 - f) + uHsl[j] * f;
                            shift += band.x * 30.0;
                            satScale += band.y;
                            lumShift += band.z * 0.25 * hsv.y;
                            break;
                        }
                    }
                }
                hsv.x = fract((h + shift) / 360.0);
                hsv.y = clamp(hsv.y * satScale, 0.0, 1.0);
                vec3 shifted = hsvToRgb(hsv);
                c = clamp(withLuma(shifted, luma(shifted), luma(shifted) + lumShift), 0.0, 1.0);
            }
            float gray = luma(c);
            float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
            c = clamp(mix(vec3(gray), c, 1.0 + uSaturation + uVibrance * (1.0 - sat)), 0.0, 1.0);

            // Filter.
            if (uLutMix > 0.0) {
                vec3 k = c * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
                c = mix(c, texture(uLut, k).rgb, uLutMix);
            }

            // Local masks.
            for (int i = 0; i < $MAX_MASKS; i++) {
                if (i >= uMaskCount) break;
                float w = maskWeight(i, s, detail);
                if (w > 0.0) c = mix(c, localAdjust(c, uMaskA[i], uMaskB[i], detail), w);
            }

            // Fade, vignette and grain.
            c = c * (1.0 - 0.18 * uFade) + 0.12 * uFade;
            if (uVignette != 0.0) {
                float d = length((uv - 0.5) * 2.0) * 0.7071;
                float w = smoothstep(0.35, 1.05, d);
                c = uVignette < 0.0 ? c * (1.0 + uVignette * w * 0.85) : mix(c, vec3(1.0), uVignette * w * 0.6);
            }
            if (uGrain > 0.0 && detailOn) {
                float n = valueNoise(s * uRaster / max(shortEdge() / 900.0, 1.0e-3)) - 0.5;
                c = clamp(c + n * uGrain * 0.18 * (1.0 - 0.5 * luma(c)), 0.0, 1.0);
            }

            // A solid redaction bar covers everything below it.
            if (redactStyle == 2) c = uRedactColor[redact].rgb;

            // Cut-out: the background removed, or replaced by a colour or a picture.
            if (uBgMode == 1) {
                alpha = subject;
            } else if (uBgMode == 2) {
                c = mix(uBgColor.rgb, c, subject);
            } else if (uBgMode == 4) {
                vec3 img = texture(uBgImage, uv * uBgImageMap.xy + uBgImageMap.zw).rgb;
                c = mix(img, c, subject);
            }
            alpha *= redact >= 0 ? 1.0 : texel.a;

            // Rounded corners show the frame's colour behind.
            vec4 picture = vec4(c * alpha, alpha);
            vec4 back = uBorderColor.a > 0.0 ? vec4(uBorderColor.rgb * uBorderColor.a, uBorderColor.a) : vec4(0.0);
            outColor = mix(back, picture, roundEdge);
        }
    """.trimIndent()
}

/** How many local masks one edit may hold (the shader's limit). */
const val MAX_MASKS = EditShader.MAX_MASKS

/** How many regions one edit may redact (the shader's limit). */
const val MAX_REDACTIONS = EditShader.MAX_REDACTIONS
