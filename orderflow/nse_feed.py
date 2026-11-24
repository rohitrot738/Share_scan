"""Authorized NSE feed boundary. No unofficial scraping or fabricated live data."""
from dataclasses import dataclass
@dataclass(frozen=True)
class FeedStatus:
    connected: bool
    source: str
    message: str
class NSEFeedAdapter:
    """Vendor transport/decoder must be implemented against an entitled feed specification."""
    def __init__(self, *, entitlement_confirmed: bool = False): self.entitlement_confirmed = entitlement_confirmed
    def status(self) -> FeedStatus:
        if not self.entitlement_confirmed: return FeedStatus(False,"NSE_AUTHORIZED_FEED","DATA UNAVAILABLE: licensed NSE feed entitlement and connection are not configured.")
        return FeedStatus(False,"NSE_AUTHORIZED_FEED","NOT IMPLEMENTED: configure the licensed vendor transport and decoder.")
    def stream(self): raise RuntimeError(self.status().message)
