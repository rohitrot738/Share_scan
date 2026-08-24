from demand_supply import detect_zones
from order_flow import order_flow_proxy, absorption_score
from bulk_order_detector import detect_abnormal_activity
from false_breakout_filter import false_breakout_risk
from entry_risk_engine import trade_plan
from vwap_orb_engine import analyse_vwap_orb


def ghost_trade_snapshot(df):
    zones = detect_zones(df)
    demand = [z for z in zones if z['type']=='demand']
    supply = [z for z in zones if z['type']=='supply']
    support = demand[0]['price'] if demand else float(df['low'].tail(20).min())
    resistance = supply[0]['price'] if supply else float(df['high'].tail(20).max())

    flow = order_flow_proxy(df)
    absorption = absorption_score(df)
    abnormal = detect_abnormal_activity(df)
    fb = false_breakout_risk(df, resistance=resistance, support=support)
    vwap_orb = analyse_vwap_orb(df)
    plan = trade_plan(df, resistance=resistance, support=support)

    score = 0.0
    score += 20 if flow['dominance']=='BUY' else 8 if flow['dominance']=='NEUTRAL' else 0
    score += 0.20*absorption
    score += 0.20*abnormal['activity_score']
    score += 15 if vwap_orb['above_vwap'] else 0
    score += 10 if vwap_orb['low_volume_pullback'] else 0
    score += 10 if vwap_orb['green_confirmation'] else 0
    score -= 0.15*fb['risk']
    score = max(0.0, min(100.0, score))

    if score >= 82 and fb['risk'] <= 35:
        signal = 'BUY_READY'
    elif score >= 68:
        signal = 'WATCH'
    elif score <= 35 and flow['dominance']=='SELL':
        signal = 'AVOID'
    else:
        signal = 'NEUTRAL'

    return {
        'ghost_score': round(score,2),
        'signal': signal,
        'demand_supply_zones': zones,
        'order_flow_proxy': flow,
        'absorption_score': absorption,
        'abnormal_activity': abnormal,
        'false_breakout': fb,
        'vwap_orb': vwap_orb,
        'trade_plan': plan,
    }
