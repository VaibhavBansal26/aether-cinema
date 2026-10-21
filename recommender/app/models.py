"""Bounded internal contracts. Admin policy is supplied only by the authenticated Java gateway."""
from datetime import datetime, timezone
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator

class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

class Title(Contract):
    id: str = Field(min_length=1, max_length=80)
    name: str = Field(min_length=1, max_length=120)
    synopsis: str = Field(default="", max_length=2000)
    genre: str = Field(min_length=1, max_length=80)
    kind: Literal["films", "series"] = "films"
    editorial_score: float = Field(default=0.5, ge=0, le=1)

class Interaction(Contract):
    title_id: str = Field(min_length=1, max_length=80)
    position: float = Field(default=0, ge=0, le=86400)
    duration: float = Field(default=1, gt=0, le=86400)
    liked: bool = False
    saved: bool = False
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @model_validator(mode="after")
    def timeline(self):
        if self.position > self.duration:
            raise ValueError("Position must not exceed duration")
        if self.updated_at.tzinfo is None:
            raise ValueError("Use a timezone-aware timestamp")
        return self

class LlmPolicy(Contract):
    enabled: bool = False
    provider: Literal["none", "ollama"] = "none"
    model: str = Field(default="", max_length=120, pattern=r"^[a-zA-Z0-9_./:-]*$")

    @model_validator(mode="after")
    def explicit_opt_in(self):
        if self.enabled and (self.provider != "ollama" or not self.model):
            raise ValueError("Enabling an LLM requires an explicit Ollama provider and model")
        return self

class RecommendationRequest(Contract):
    catalog: list[Title] = Field(max_length=500)
    interactions: list[Interaction] = Field(default_factory=list, max_length=1000)
    policy: LlmPolicy = Field(default_factory=LlmPolicy)
    row_size: int = Field(default=8, ge=2, le=20)

    @model_validator(mode="after")
    def unique_ids(self):
        if len({t.id for t in self.catalog}) != len(self.catalog):
            raise ValueError("Catalog IDs must be unique")
        if len({i.title_id for i in self.interactions}) != len(self.interactions):
            raise ValueError("Interactions must be aggregated per title")
        return self
