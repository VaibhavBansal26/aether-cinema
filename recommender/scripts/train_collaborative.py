"""Train on aggregated JSONL: profile_id, title_id, positive weight. Never use raw PII."""
import argparse
import json
from pathlib import Path
from app.collaborative import train

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("events",type=Path)
    parser.add_argument("output",type=Path)
    parser.add_argument("--components",type=int,default=32)
    args=parser.parse_args()
    records=[json.loads(line) for line in args.events.read_text().splitlines() if line.strip()]
    print(json.dumps(train(records,args.output,args.components)))
