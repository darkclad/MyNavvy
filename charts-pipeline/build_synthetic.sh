#!/usr/bin/env bash
# Synthetic foreign chart tier hook, called by build_region.sh step [5/8]:
#   build_synthetic.sh <region work dir> <XMIN> <YMIN> <XMAX> <YMAX>
# Appends SYN=1 features (GEBCO/NONNA depth areas + OSM coastline + seamarks + DATLIM)
# into <RDIR>/charts.gpkg and writes <RDIR>/synthetic_meta. No-op inside US-only regions.
set -euo pipefail
RDIR=$1; XMIN=$2; YMIN=$3; XMAX=$4; YMAX=$5
PIPE=/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline
CACHE=/root/mynavvy_work/cache

python3 -c "import numpy" 2>/dev/null || \
  DEBIAN_FRONTEND=noninteractive apt-get install -y python3-numpy >/dev/null

# NB --bbox= (equals form): the value starts with a minus sign and argparse would
# otherwise read it as an option.
python3 "$PIPE/make_synthetic.py" \
  --gpkg "$RDIR/charts.gpkg" \
  --bbox="$XMIN,$YMIN,$XMAX,$YMAX" \
  --cells "$RDIR/cells.tsv" \
  --cache "$CACHE" \
  --work "$RDIR" \
  --meta "$RDIR/synthetic_meta"
