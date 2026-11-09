#!/usr/bin/env python3
"""Make the TFLite model's tensor shape signatures static.

onnx2tf keeps -1 in each tensor's `shape_signature` for batch/height/width even
though the converter did resolve a concrete `shape`. TFLite operators (SLICE,
RESHAPE, ...) treat a -1 signature dimension as "not specified" and mark their
output tensors as dynamic; the GPU delegate then refuses the whole graph:

    Attempting to use a delegate that only supports static-sized tensors with a
    graph that has dynamic-sized tensors.

This rewrites every signature to the concrete shape (in place: the signature
vector already has the same rank, so no offsets or sizes change). Run it after
re-exporting the model with onnx2tf.

Usage: staticize_tflite_shapes.py [--check] [model.tflite]
"""

import os
import struct
import sys
import tempfile

DEFAULT_MODEL = "app/src/main/assets/models/yolo26n_pose.tflite"

# The bundled pose model's graph contract. A layout swap such as [1, 8400, 56] has the same
# element count, so the app cannot tell it apart by size alone; reject it here.
POSE_INPUT_SHAPE = [1, 640, 640, 3]
POSE_OUTPUT_SHAPE = [1, 56, 8400]
FLOAT32 = 0


def u32(data, pos):
    return struct.unpack_from("<I", data, pos)[0]


def i32(data, pos):
    return struct.unpack_from("<i", data, pos)[0]


def u16(data, pos):
    return struct.unpack_from("<H", data, pos)[0]


def field(data, table, index):
    """Absolute position of a table field, or 0 when it is absent."""
    vtable = table - i32(data, table)
    field_offset = 4 + index * 2
    if field_offset + 2 > u16(data, vtable):
        return 0
    offset = u16(data, vtable + field_offset)
    return table + offset if offset else 0


def table_at(data, pos):
    """Resolve a vector element (a uoffset) to the table it points at."""
    return pos + u32(data, pos)


def vector(data, table, index):
    """(data position, length) of a vector field, or None when absent."""
    pos = field(data, table, index)
    if not pos:
        return None
    vec = pos + u32(data, pos)
    return vec + 4, u32(data, vec)


def int_vector(data, table, index):
    field_pos = field(data, table, index)
    if not field_pos:
        return []
    vec = field_pos + u32(data, field_pos)
    return list(struct.unpack_from("<%di" % u32(data, vec), data, vec + 4))


def tensor_tables(data):
    # Root Model field 2 is subgraphs; SubGraph field 0 is tensors.
    subgraphs = vector(data, u32(data, 0), 2)
    if subgraphs is None:
        raise SystemExit("model has no subgraphs")
    start, count = subgraphs
    for index in range(count):
        tensors = vector(data, table_at(data, start + index * 4), 0)
        if tensors is None:
            continue
        tensor_start, tensor_count = tensors
        for tensor_index in range(tensor_count):
            yield table_at(data, tensor_start + tensor_index * 4)


def tensor_name(data, tensor):
    pos = field(data, tensor, 3)
    if not pos:
        return ""
    vec = pos + u32(data, pos)
    length = u32(data, vec)
    return data[vec + 4:vec + 4 + length].decode("utf-8", "replace")


def tensor_type(data, tensor):
    # Tensor field 1 is the element type; the FlatBuffer omits a zero (FLOAT32) default.
    pos = field(data, tensor, 1)
    return data[pos] if pos else FLOAT32


def tensor_indices(data, subgraph, index):
    vector_pos = vector(data, subgraph, index)
    if vector_pos is None:
        return []
    start, count = vector_pos
    return list(struct.unpack_from("<%di" % count, data, start))


def graph_tensors(data):
    """(name, shape, type) for the first subgraph's inputs and outputs."""
    subgraphs = vector(data, u32(data, 0), 2)
    if subgraphs is None:
        raise SystemExit("model has no subgraphs")
    subgraph_start, _ = subgraphs
    subgraph = table_at(data, subgraph_start)
    tensors = vector(data, subgraph, 0)
    if tensors is None:
        raise SystemExit("model has no tensors")
    tensor_start, _ = tensors

    def describe(index):
        tensor = table_at(data, tensor_start + index * 4)
        return tensor_name(data, tensor), int_vector(data, tensor, 0), tensor_type(data, tensor)

    inputs = [describe(i) for i in tensor_indices(data, subgraph, 1)]
    outputs = [describe(i) for i in tensor_indices(data, subgraph, 2)]
    return inputs, outputs


def validate_pose_contract(data):
    inputs, outputs = graph_tensors(data)
    if len(inputs) != 1 or len(outputs) != 1:
        raise SystemExit(
            "expected one model input and output, got %d/%d" % (len(inputs), len(outputs))
        )
    in_name, in_shape, in_type = inputs[0]
    out_name, out_shape, out_type = outputs[0]
    if in_shape != POSE_INPUT_SHAPE:
        raise SystemExit(
            "unexpected input %s shape %s; expected %s" % (in_name, in_shape, POSE_INPUT_SHAPE)
        )
    if out_shape != POSE_OUTPUT_SHAPE:
        raise SystemExit(
            "unexpected output %s shape %s; expected value-major %s"
            % (out_name, out_shape, POSE_OUTPUT_SHAPE)
        )
    if in_type != FLOAT32 or out_type != FLOAT32:
        raise SystemExit(
            "model input/output must be float32, got element types %d/%d" % (in_type, out_type)
        )


def write_atomically(path, data):
    """Replace path only after the new bytes are fully written and flushed."""
    directory = os.path.dirname(os.path.abspath(path))
    fd, tmp_path = tempfile.mkstemp(
        dir=directory, prefix=os.path.basename(path) + ".", suffix=".tmp"
    )
    try:
        with os.fdopen(fd, "wb") as tmp_file:
            tmp_file.write(data)
            tmp_file.flush()
            os.fsync(tmp_file.fileno())
        os.replace(tmp_path, path)
    except BaseException:
        try:
            os.unlink(tmp_path)
        except FileNotFoundError:
            pass
        raise


def main():
    args = [a for a in sys.argv[1:] if a != "--check"]
    check_only = "--check" in sys.argv[1:]
    path = args[0] if args else DEFAULT_MODEL

    data = bytearray(open(path, "rb").read())
    patched = unresolved = 0
    for tensor in tensor_tables(data):
        shape = int_vector(data, tensor, 0)
        signature = field(data, tensor, 7)
        if not signature:
            continue
        signature += u32(data, signature)
        length = u32(data, signature)
        values = list(struct.unpack_from("<%di" % length, data, signature + 4))
        if -1 not in values:
            continue
        if len(values) != len(shape) or -1 in shape:
            unresolved += 1
            continue
        if not check_only:
            struct.pack_into("<%di" % length, data, signature + 4, *shape)
        patched += 1

    # A -1 that has no concrete shape to take its place would still make the
    # graph dynamic; re-export with fixed input shapes instead.
    if unresolved:
        raise SystemExit(
            "%d tensor(s) have unset dims in both shape and shape_signature" % unresolved
        )

    if check_only:
        if patched:
            raise SystemExit("%d tensor(s) still have a dynamic shape signature" % patched)
        validate_pose_contract(data)
        print("all shape signatures are static")
        return

    write_atomically(path, data)
    print("patched %d tensor shape signature(s) in %s" % (patched, path))


if __name__ == "__main__":
    main()
