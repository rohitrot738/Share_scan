"""Fusion layer between Ghost Trade Pro and 360CR.

Technical timing remains dominant for entry/exit. 360CR acts as a conviction,
quality and risk filter so weak fundamentals/ownership can veto or downgrade a
technically attractive setup, while strong fundamentals can modestly increase
confidence. This avoids buying a poor business only because of one chart spike.
"""
from __future__ import annotations

from typing import Any, Dict, Mapping


def _clip(x, lo=0.0, hi=100.0):
    return max(lo,min(hi,float(x)))


def fuse_technical_360cr(technical: Mapping[str,Any], cr360: Mapping[str,Any]) -> Dict[str,Any]:
    tscore=float(technical.get('final_score', technical.get('decision',{}).get('score',0)))
    cdec=cr360.get('decision',{})
    cscore=float(cdec.get('score',0))
    false_risk=float(technical.get('false_breakout_risk', technical.get('decision',{}).get('false_breakout_risk',50)))
    red=int(cdec.get('red_flag_count',0))
    green=int(cdec.get('green_flag_count',0))
    bias=str(cdec.get('fundamental_bias','NEUTRAL'))
    conviction=str(cdec.get('conviction','LOW'))
    ownership=str(cdec.get('ownership_bias','UNKNOWN'))
    mos=float(cdec.get('margin_of_safety_pct',0))

    # Technical setup controls timing; 360CR controls conviction and vetoes.
    base=0.68*tscore+0.32*cscore

    bonus=0.0
    if bias=='BULLISH': bonus+=3.0
    if conviction=='HIGH': bonus+=2.5
    if ownership=='ACCUMULATION': bonus+=2.0
    if mos>=15: bonus+=2.0
    if green>=6: bonus+=1.5

    penalty=0.0
    if bias=='BEARISH': penalty+=8.0
    if conviction=='LOW': penalty+=4.0
    if ownership=='DISTRIBUTION': penalty+=5.0
    if mos<=-25: penalty+=5.0
    if red>=5: penalty+=6.0
    if str(cdec.get('cashflow_flag'))=='WEAK': penalty+=4.0
    if str(cdec.get('balance_sheet_flag'))=='RISKY': penalty+=5.0
    if str(cdec.get('earnings_trend'))=='DETERIORATING': penalty+=5.0

    # Technical trap risk remains a hard limiter.
    if false_risk>=60: penalty+=8.0
    elif false_risk>=45: penalty+=4.0

    final=_clip(base+bonus-penalty)

    # Hard vetoes prevent a high score from hiding material risk.
    hard_veto=False
    veto_reasons=[]
    if false_risk>=75:
        hard_veto=True; veto_reasons.append('extreme technical false-breakout risk')
    if cscore<40 and red>=5:
        hard_veto=True; veto_reasons.append('weak 360CR with multiple red flags')
    if str(cdec.get('balance_sheet_flag'))=='RISKY' and str(cdec.get('cashflow_flag'))=='WEAK':
        hard_veto=True; veto_reasons.append('balance-sheet and cash-flow risk together')

    if hard_veto:
        state='AVOID'
        final=min(final,49.0)
    elif final>=88 and false_risk<=30 and cscore>=70:
        state='A+ CONFIRMED'
    elif final>=80 and false_risk<=40:
        state='CONFIRMED'
    elif final>=72:
        state='READY'
    elif final>=62:
        state='EARLY'
    elif final>=52:
        state='WATCH'
    else:
        state='IGNORE'

    return {
        'final_fused_score':round(final,2),
        'final_state':state,
        'technical_score':round(tscore,2),
        'cr360_score':round(cscore,2),
        'technical_false_breakout_risk':round(false_risk,2),
        'fundamental_bias':bias,
        '360cr_conviction':conviction,
        'ownership_bias':ownership,
        'margin_of_safety_pct':round(mos,2),
        'hard_veto':hard_veto,
        'veto_reasons':veto_reasons,
        'bonus':round(bonus,2),
        'penalty':round(penalty,2),
        'entry':technical.get('entry', technical.get('decision',{}).get('entry')),
        'stop':technical.get('stop', technical.get('decision',{}).get('stop')),
        'target1':technical.get('target1', technical.get('decision',{}).get('target1')),
        'target2':technical.get('target2', technical.get('decision',{}).get('target2')),
        'target3':technical.get('target3', technical.get('decision',{}).get('target3')),
    }


def decision_explanation(fused: Mapping[str,Any]) -> str:
    return (
        f"{fused['final_state']} | Fused {fused['final_fused_score']:.1f}/100 | "
        f"Technical {fused['technical_score']:.1f} | 360CR {fused['cr360_score']:.1f} | "
        f"False-break {fused['technical_false_breakout_risk']:.1f} | "
        f"Fundamental {fused['fundamental_bias']} | Ownership {fused['ownership_bias']}"
    )
