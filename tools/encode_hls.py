#!/usr/bin/env python3
"""Encode a local GB movie into a flat, private-gateway-compatible HLS bundle. No cloud credentials.
Usage: python3 tools/encode_hls.py /absolute/movie.mp4 /new/output/directory
Requires locally installed FFmpeg/ffprobe. Originals remain on your disk; only renditions use R2 space.
"""
import argparse,json,math,pathlib,subprocess
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('input',type=pathlib.Path);parser.add_argument('output',type=pathlib.Path);args=parser.parse_args()
if not args.input.is_file():parser.error('Input video does not exist')
args.output.mkdir(parents=True,exist_ok=False)
probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_entries','stream=width,height','-of','json',str(args.input)],text=True))['streams'][0]
height=int(probe['height']);width=int(probe['width']);ladder=[(h,b) for h,b in [(360,600),(720,1800),(1080,3500)] if h<=height]
if not ladder:ladder=[(max(2,height//2*2),400)]
master=['#EXTM3U','#EXT-X-VERSION:3']
for h,bitrate in ladder:
 w=max(2,round(width*h/height/2)*2)
 subprocess.run(['ffmpeg','-nostdin','-hide_banner','-y','-i',str(args.input),'-map','0:v:0','-map','0:a:0?','-vf',f'scale={w}:{h}','-c:v','libx264','-preset','medium','-pix_fmt','yuv420p','-b:v',f'{bitrate}k','-maxrate',f'{bitrate}k','-bufsize',f'{bitrate*2}k','-force_key_frames','expr:gte(t,n_forced*4)','-c:a','aac','-b:a','96k','-ac','2','-f','hls','-hls_time','4','-hls_playlist_type','vod','-hls_flags','independent_segments','-hls_segment_filename',str(args.output/f'v{h}_%06d.ts'),str(args.output/f'v{h}.m3u8')],check=True)
 master.extend([f'#EXT-X-STREAM-INF:BANDWIDTH={(bitrate+160)*1000},RESOLUTION={w}x{h}',f'v{h}.m3u8'])
(args.output/'master.m3u8').write_text('\n'.join(master)+'\n')
files=list(args.output.iterdir());total=sum(p.stat().st_size for p in files)
if len(files)>20000 or total>8000000000 or any(p.stat().st_size>16777216 for p in files):raise SystemExit('Bundle exceeds the free-demo storage/segment limits. Reduce resolution, bitrate or duration before uploading.')
print(json.dumps({'files':len(files),'bytes':total,'directory':str(args.output.resolve()),'original_uploaded':False},indent=2))
