package com.llmagent.common

import zio.*
import zio.test.*
import com.llmagent.common.observability.Types.ConversationId

object TestHelpers:

  def testConversationId: ConversationId = ConversationId.unsafe("test-conversation-id")

  def testTraceId: Types.TraceId = Types.TraceId.unsafe("test-trace-id")

  def uniqueConversationId: ConversationId = ConversationId.generate()

  def uniqueTraceId: Types.TraceId = Types.TraceId.generate()
