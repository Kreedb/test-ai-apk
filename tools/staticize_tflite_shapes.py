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

import struct
import sys

DEFAULT_MODEL = "app/src/main/assets/models/yolo26n_pose.tflite"


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
        print("all shape signatures are static")
        return

    open(path, "wb").write(data)
    print("patched %d tensor shape signature(s) in %s" % (patched, path))


if __name__ == "__main__":
    main()
