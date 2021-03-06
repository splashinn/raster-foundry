package com.azavea.rf.api.utils

import io.circe.generic.JsonCodec

@JsonCodec
case class ManagementBearerToken(access_token: String, expires_in: Int, token_type: String, scope: String)
