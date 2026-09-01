from typing import Dict
import pandas as pd
from config import ScannerConfig
from pattern_engine import extract_features
from advanced_scoring import score_features_advanced
from training_cases.yatharth_case_003 import CASE_ID as YATHARTH_CASE_ID, GENERIC_RULES as YATHARTH_RULES
TF_WEIGHTS={'1m':.05,'5m':.15,'15m':.15,'30m':.10,'1h':.15,'4h':.10,'1d':.20,'1w':.10}
def _pre_breakout_bonus(tf,df):
    if df is None or len(df)<25:return 0.0
    x=df.copy(); x.columns=[str(c).lower() for c in x.columns]; required={'high','low','close','volume'}
    if not required.issubset(x.columns):return 0.0
    x=x.dropna(subset=['high','low','close','volume']);
    if len(x)<25:return 0.0
    recent=x.iloc[-20:]; close=float(x.close.iloc[-1]); prior=float(x.high.iloc[-21:-1].max()); low=float(recent.low.min()); width=(prior-low)/max(close,1e-9)*100; dist=(prior-close)/max(prior,1e-9)*100; vr=float(x.volume.iloc[-1])/max(float(x.volume.iloc[-21:-1].median()),1); move=abs(float(x.close.iloc[-1])/max(float(x.close.iloc[-2]),1e-9)-1)*100; r=YATHARTH_RULES; b=3 if width<=r['base_width_pct']['strong'] else (1.5 if width<=r['base_width_pct']['moderate'] else 0); b+=5 if 0<=dist<=r['resistance_proximity_pct']['strong'] else (3 if dist<=r['resistance_proximity_pct']['moderate'] else 0); b+=4 if vr>=r['volume_ratio']['strong'] else (2 if vr>=r['volume_ratio']['moderate'] else 0); b-=5 if move>=r['extension_limit_pct'].get(tf,r['extension_limit_pct']['default']) else 0; return b
def analyse_timeframes(data:Dict[str,pd.DataFrame],cfg:ScannerConfig):
    per_tf={}; weighted=used=0.0
    for tf,df in data.items():
        f=extract_features(df,cfg); score=score_features_advanced(f,cfg); bonus=_pre_breakout_bonus(tf,df); score['base_score']=score['score']; score['pre_breakout_bonus']=round(bonus,2); score['score']=round(max(0,min(100,score['score']+bonus)),2); per_tf[tf]=score; w=TF_WEIGHTS.get(tf,.05); weighted+=score['score']*w; used+=w
    final=weighted/used if used else 0; states=[x['state'] for x in per_tf.values()]; high=[per_tf[t]['score'] for t in ('1h','4h','1d','1w') if t in per_tf]; trigger=[per_tf[t]['score'] for t in ('5m','15m','30m') if t in per_tf]; high_ok=sum(high)/len(high)>=72 if high else True; trigger_ok=sum(trigger)/len(trigger)>=72 if trigger else True
    state='CONFIRMED' if final>=88 and 'CONFIRMED' in states and high_ok and trigger_ok else ('READY' if final>=78 and high_ok else ('EARLY' if final>=65 else 'IGNORE'))
    return {'final_score':round(final,2),'final_state':state,'high_tf_ok':high_ok,'trigger_ok':trigger_ok,'training_case':YATHARTH_CASE_ID,'timeframes':per_tf}
