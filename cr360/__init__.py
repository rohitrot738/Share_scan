"""360CR — 360 Degree Conviction Research."""
from .engine import analyse_360cr, analyse_many_360cr
from .models import ResearchInput, ResearchResult

__all__ = ["analyse_360cr", "analyse_many_360cr", "ResearchInput", "ResearchResult"]
