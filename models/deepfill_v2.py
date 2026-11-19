"""Builds deepfill_v2_512_fp16.tflite, the object eraser's DeepFill v2 model (spec §4.10).

DeepFill v2 (Yu et al., "Free-Form Image Inpainting with Gated Convolution", ICCV 2019), the
official Places2 release (github.com/JiahuiYu/generative_inpainting, CC BY-NC 4.0). Its weights
are read from a tf2onnx export of the official TF graph on Hugging Face, pinned below. No LiteRT
build of DeepFill v2 existed, so the generator is rebuilt here in TF2 and converted.

The rebuild follows the official build_inpaint_net and contextual_attention, except that patches
are cut by padding, slicing and space_to_depth instead of extract_image_patches, and attention
matches and pastes through matrix multiplies instead of convolutions whose filters are tensors,
so LiteRT runs it with builtin ops. Resizes keep the official nearest-neighbour align_corners
semantics (the ONNX export rounds them down instead, which smears its fills).

Interface, the same as LaMa's file: image float32 [1, 3, 512, 512] RGB in 0..1, mask float32
[1, 1, 512, 512] with 1 = fill; output float32 [1, 3, 512, 512] RGB in 0..1, the input kept
outside the mask. Inside, the network sees BGR in -1..1, as it was trained on cv2.imread.

Run: python3 deepfill_v2.py [out.tflite]  (needs tensorflow, onnx, numpy, pillow,
ai-edge-litert). Check: the rebuild must reproduce the official Places2 example outputs within
1 level, and the fp16 file the fp32 rebuild within 1 level; the script stops otherwise.
"""
import hashlib, io, sys, urllib.request
import numpy as np, onnx, tensorflow as tf
from onnx import numpy_helper
from PIL import Image

ONNX_URL = ("https://huggingface.co/vooxyou/deepfillv2-inpainting/resolve/"
            "449eacb293e129cd76e8e426781dd3384830060a/deepfillv2_quantized.onnx")
ONNX_SHA256 = "02312b5058c8bad86e56633479490d25fce0cbc8686f4509e6781996167f47de"
EXAMPLES = "https://raw.githubusercontent.com/JiahuiYu/generative_inpainting/v2.0.0/examples/places2/"
SIDE = 512


def fetch(url, sha256=None):
    data = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "PhotoXT"})).read()
    if sha256 and hashlib.sha256(data).hexdigest() != sha256:
        sys.exit("checksum mismatch: " + url)
    return data


def load_weights():
    """Gated-conv kernels (HWIO) and biases by layer name, from the ONNX export (float32 inside,
    despite the file's name)."""
    m = onnx.load_from_string(fetch(ONNX_URL, ONNX_SHA256))
    inits = {t.name: numpy_helper.to_array(t) for t in m.graph.initializer}
    w = {}
    for n in m.graph.node:
        if n.op_type == "Conv" and len(n.input) > 2 and n.input[1] in inits:
            w[n.name.split("/")[1]] = (np.transpose(inits[n.input[1]], (2, 3, 1, 0)).copy(),
                                       inits[n.input[2]].copy())
    return w


def nn_resize(x, h, w):
    return tf.compat.v1.image.resize_nearest_neighbor(x, [h, w], align_corners=True)


class DeepFill(tf.Module):
    def __init__(self, weights):
        super().__init__()
        self.w = {k: (tf.constant(a), tf.constant(b)) for k, (a, b) in weights.items()}

    def conv(self, x, name, stride=1, rate=1, act="elu"):
        k, b = self.w[name]
        x = tf.nn.conv2d(x, k, strides=stride, padding="SAME", dilations=rate) + b
        if k.shape[-1] == 3 or act is None:
            return x
        x, y = tf.split(x, 2, axis=3)
        x = tf.nn.elu(x) if act == "elu" else tf.nn.relu(x)
        return x * tf.sigmoid(y)

    def deconv(self, x, name):
        return self.conv(nn_resize(x, x.shape[1] * 2, x.shape[2] * 2), name)

    @staticmethod
    def patches3(x, h, w):
        """3x3 SAME patches at stride 1: [1, h, w, 9C], ordered (dy, dx, c)."""
        p = tf.pad(x, [[0, 0], [1, 1], [1, 1], [0, 0]])
        return tf.concat([p[:, dy:dy + h, dx:dx + w, :] for dy in range(3) for dx in range(3)], 3)

    def attention(self, f, b, mask, rate=2, scale=10.0):
        """contextual_attention(f, b, mask, ksize=3, stride=1, rate=2, fuse_k=3, softmax_scale=10)."""
        _, H, W, C = f.shape
        h, w = H // rate, W // rate
        L = h * w
        # Background patches 4x4 at stride 2 (SAME pads one each side), ordered (a, b, r, s, c)
        # with ky = 2a + r, kx = 2b + s: four shifted blocks of the padded background's space_to_depth.
        s2d = tf.nn.space_to_depth(tf.pad(b, [[0, 0], [1, 1], [1, 1], [0, 0]]), 2)
        raw = tf.concat([s2d[:, a:a + h, c:c + w, :] for a in (0, 1) for c in (0, 1)], 3)
        raw = tf.reshape(raw, [L, 16 * C])
        fd, bd, md = nn_resize(f, h, w), nn_resize(b, h, w), nn_resize(mask, h, w)
        fp = tf.reshape(self.patches3(fd, h, w), [L, 9 * C])
        bp = tf.reshape(self.patches3(bd, h, w), [L, 9 * C])
        bp = bp / tf.maximum(tf.sqrt(tf.reduce_sum(tf.square(bp), 1, keepdims=True)), 1e-4)
        y = tf.matmul(fp, bp, transpose_b=True)  # [foreground, background]

        def fuse(t):  # the 3x3 identity convolution: a sum along the diagonal
            p = tf.pad(t, [[1, 1], [1, 1]])
            return p[0:L, 0:L] + p[1:L + 1, 1:L + 1] + p[2:L + 2, 2:L + 2]
        y = fuse(y)
        y = tf.reshape(tf.transpose(tf.reshape(y, [h, w, h, w]), [1, 0, 3, 2]), [L, L])
        y = fuse(y)
        y = tf.reshape(tf.transpose(tf.reshape(y, [w, h, w, h]), [1, 0, 3, 2]), [L, L])
        # Only background patches clear of the hole are pasted from.
        mm = tf.cast(tf.equal(tf.reduce_mean(self.patches3(md, h, w), 3), 0.0), tf.float32)
        mm = tf.reshape(mm, [1, L])
        y = y * mm * scale
        y = tf.nn.softmax(y - tf.reduce_max(y, 1, keepdims=True), 1) * mm  # max out: safe on GPUs
        # Paste: weighted patches overlap-added at stride 2 (conv2d_transpose, SAME, then / 4).
        p = tf.reshape(tf.matmul(y, raw), [1, h, w, 16 * C])
        out = None
        for i, (a, c) in enumerate(((0, 0), (0, 1), (1, 0), (1, 1))):
            img = tf.nn.depth_to_space(p[:, :, :, i * 4 * C:(i + 1) * 4 * C], 2)
            img = tf.pad(img, [[0, 0], [2 * a, 2 - 2 * a], [2 * c, 2 - 2 * c], [0, 0]])
            out = img if out is None else out + img
        return out[:, 1:H + 1, 1:W + 1, :] / 4.0

    def net(self, xin, mask):
        ones = tf.ones_like(mask)
        cv = self.conv
        x = cv(tf.concat([xin, ones, ones * mask], 3), "conv1")
        x = cv(x, "conv2_downsample", 2); x = cv(x, "conv3")
        x = cv(x, "conv4_downsample", 2); x = cv(x, "conv5"); x = cv(x, "conv6")
        mask_s = nn_resize(mask, x.shape[1], x.shape[2])
        x = cv(x, "conv7_atrous", rate=2); x = cv(x, "conv8_atrous", rate=4)
        x = cv(x, "conv9_atrous", rate=8); x = cv(x, "conv10_atrous", rate=16)
        x = cv(x, "conv11"); x = cv(x, "conv12")
        x = self.deconv(x, "conv13_upsample"); x = cv(x, "conv14")
        x = self.deconv(x, "conv15_upsample"); x = cv(x, "conv16")
        x = tf.tanh(cv(x, "conv17", act=None))
        xnow = x * mask + xin * (1.0 - mask)
        x = cv(xnow, "xconv1"); x = cv(x, "xconv2_downsample", 2); x = cv(x, "xconv3")
        x = cv(x, "xconv4_downsample", 2); x = cv(x, "xconv5"); x = cv(x, "xconv6")
        x = cv(x, "xconv7_atrous", rate=2); x = cv(x, "xconv8_atrous", rate=4)
        x = cv(x, "xconv9_atrous", rate=8); hallu = cv(x, "xconv10_atrous", rate=16)
        x = cv(xnow, "pmconv1"); x = cv(x, "pmconv2_downsample", 2); x = cv(x, "pmconv3")
        x = cv(x, "pmconv4_downsample", 2); x = cv(x, "pmconv5"); x = cv(x, "pmconv6", act="relu")
        x = self.attention(x, x, mask_s)
        x = cv(x, "pmconv9"); x = cv(x, "pmconv10")
        x = cv(tf.concat([hallu, x], 3), "allconv11"); x = cv(x, "allconv12")
        x = self.deconv(x, "allconv13_upsample"); x = cv(x, "allconv14")
        x = self.deconv(x, "allconv15_upsample"); x = cv(x, "allconv16")
        return tf.tanh(cv(x, "allconv17", act=None))

    def inpaint(self, image, mask):
        """image [1,3,H,W] RGB 0..1, mask [1,1,H,W] (1 = fill) -> [1,3,H,W] RGB 0..1."""
        rgb = tf.transpose(image, [0, 2, 3, 1])
        m = tf.cast(tf.transpose(mask, [0, 2, 3, 1]) > 0.5, tf.float32)
        bgr = tf.concat([rgb[..., 2:3], rgb[..., 1:2], rgb[..., 0:1]], 3)
        xin = (bgr * 2.0 - 1.0) * (1.0 - m)
        done = (self.net(xin, m) * m + xin * (1.0 - m) + 1.0) * 0.5
        out = tf.concat([done[..., 2:3], done[..., 1:2], done[..., 0:1]], 3)
        return tf.transpose(tf.clip_by_value(out, 0.0, 1.0), [0, 3, 1, 2])


def png(name, mode="RGB"):
    return np.asarray(Image.open(io.BytesIO(fetch(EXAMPLES + name))).convert(mode))


def check_examples(model):
    """The rebuild against the official outputs of the official examples (512 x 680)."""
    for case in ("case1", "case4"):
        img = png(case + "_input.png").astype(np.float32) / 255
        hole = (png(case + "_mask.png")[..., 0] > 127.5).astype(np.float32)
        out = model.inpaint(tf.constant(img.transpose(2, 0, 1)[None]), tf.constant(hole[None, None]))
        out = np.round(out.numpy()[0].transpose(1, 2, 0) * 255).astype(int)
        d = np.abs(out - png(case + "_output.png").astype(int))
        print(f"{case}: against the official output, mean {d.mean():.3f}, max {d.max()}")
        if d.max() > 1:
            sys.exit("the rebuild doesn't match the official DeepFill v2")


def convert(model, side):
    @tf.function(input_signature=[tf.TensorSpec([1, 3, side, side], tf.float32, name="image"),
                                  tf.TensorSpec([1, 1, side, side], tf.float32, name="mask")])
    def serve(image, mask):
        return {"output": model.inpaint(image, mask)}
    conv = tf.lite.TFLiteConverter.from_concrete_functions([serve.get_concrete_function()], model)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.target_spec.supported_types = [tf.float16]
    return conv.convert()


def check_file(model, flat, side):
    """The fp16 file against the fp32 rebuild on a 512 crop of example 1."""
    from ai_edge_litert.interpreter import Interpreter
    img = png("case1_input.png")[:side, 84:84 + side].astype(np.float32) / 255
    hole = (png("case1_mask.png")[:side, 84:84 + side, 0] > 127.5).astype(np.float32)
    x, m = img.transpose(2, 0, 1)[None], hole[None, None]
    want = model.inpaint(tf.constant(x), tf.constant(m)).numpy()
    it = Interpreter(model_content=flat)
    it.allocate_tensors()
    for d in it.get_input_details():
        it.set_tensor(d["index"], x if d["shape"][1] == 3 else m)
    it.invoke()
    d = np.abs(it.get_tensor(it.get_output_details()[0]["index"]) - want) * 255
    print(f"fp16 file against the fp32 rebuild: mean {d.mean():.3f}, max {d.max():.2f} levels")
    if d.max() > 1:
        sys.exit("the converted file drifts from the rebuild")


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "deepfill_v2_512_fp16.tflite"
    model = DeepFill(load_weights())
    check_examples(model)
    flat = convert(model, SIDE)
    check_file(model, flat, SIDE)
    open(out, "wb").write(flat)
    print(out, len(flat), "bytes, SHA-256", hashlib.sha256(flat).hexdigest())
