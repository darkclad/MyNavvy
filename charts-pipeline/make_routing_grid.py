#!/usr/bin/env python3
"""
Encode the draft-aware routing mask from a band-ordered classification + depth raster.

  class.tif : 0 = unknown, 1 = land, 2 = water     (rasterized coarse -> fine, fine wins)
  depth.tif : DEPARE.DRVAL1 in metres              (rasterized coarse -> fine, fine wins)

Output routing_grid.png:
  pixel 0        = land            -> blocked
  pixel 255      = unknown/deep    -> navigable (no charted depth area here)
  pixel 1..254   = charted depth   -> depth_m = (pixel-1)/253 * 50

Why band order matters: NOAA ENC usage bands are generalisations of one another. The overview
band draws LNDARE straight across San Diego Bay. Rasterizing every band together made the router
believe the bay was dry land. Finer bands must overwrite coarser ones.
"""
import sys, json
from osgeo import gdal
import numpy as np

class_path, depth_path, png_path, json_path = sys.argv[1:5]
cds = gdal.Open(class_path)
dds = gdal.Open(depth_path)
cls = cds.GetRasterBand(1).ReadAsArray().astype("uint8")
dep = dds.GetRasterBand(1).ReadAsArray().astype("float32")
h, w = cls.shape

out = np.full((h, w), 255, dtype=np.uint8)             # default: unknown -> navigable
water = cls == 2
has_depth = water & (dep > -9998.0)
enc = (1.0 + np.clip(dep, 0.0, 50.0) / 50.0 * 253.0).astype("uint8")
out[has_depth] = enc[has_depth]
out[water & ~has_depth] = 254                          # water, depth unknown -> treat as deep
out[cls == 1] = 0                                      # land -> blocked

mem = gdal.GetDriverByName("MEM").Create("", w, h, 1, gdal.GDT_Byte)
mem.GetRasterBand(1).WriteArray(out)
gdal.GetDriverByName("PNG").CreateCopy(png_path, mem)

gt = cds.GetGeoTransform()
west, north = gt[0], gt[3]
east, south = west + gt[1] * w, north + gt[5] * h
json.dump({
    "west": west, "east": east, "south": south, "north": north,
    "width": w, "height": h, "depthMaxM": 50, "landValue": 0, "unknownValue": 255
}, open(json_path, "w"))

land = int((out == 0).sum()); wat = int(((out > 0) & (out < 255)).sum()); unk = int((out == 255).sum())
print(f"encoded {w}x{h}  land={land} water={wat} unknown={unk}")
print(f"bounds {west:.3f},{south:.3f} -> {east:.3f},{north:.3f}")
