"""Temporary review helper: joins raw `screencap` frames into an mp4.
Usage: python3 stitch-burst.py <folder of .raw frames> <start epoch seconds> <end epoch seconds> <out.mp4>"""
import glob
import os
import struct
import subprocess
import sys

folder, started, ended, out = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
frames = sorted(glob.glob(os.path.join(folder, '*.raw')))
width, height = struct.unpack('<II', open(frames[0], 'rb').read(8))
size = width * height * 4
joined_path = os.path.join(folder, 'all.rgba')
with open(joined_path, 'wb') as joined:
    for frame in frames:
        data = open(frame, 'rb').read()
        joined.write(data[len(data) - size:])
fps = len(frames) / (ended - started)
print('burst: %d frames at %.1f fps' % (len(frames), fps))
subprocess.run(
    ['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgba', '-s', '%dx%d' % (width, height),
     '-r', '%.2f' % fps, '-i', joined_path, '-vf', 'scale=540:-2', '-pix_fmt', 'yuv420p',
     '-c:v', 'libx264', '-crf', '20', out],
    check=True,
)
