package io.github.serogee.hive.core.transfer

/** A prompt that people can copy into an external AI tool to turn notes into a Hive JSON file. */
object AiFormattingPrompt {
    val text: String = """
        Convert my questions into a Hive stack file. Reply with JSON only, no other text.

        Use this structure:
        {
          "format": "hive-stack",
          "version": 1,
          "title": "Stack title",
          "description": "Optional description",
          "questions": [
            {
              "type": "multiple_choice",
              "prompt": "Which OSI layer routes packets between networks?",
              "choices": [
                {"text": "Data link"},
                {"text": "Network", "correct": true},
                {"text": "Transport"}
              ],
              "explanation": "Optional. Why the answer is correct.",
              "points": 1,
              "seconds": 30
            },
            {
              "type": "true_false",
              "prompt": "TCP delivers bytes in the order they were sent.",
              "answer": true
            },
            {
              "type": "identification",
              "prompt": "Which protocol translates domain names into IP addresses?",
              "answer": "DNS",
              "alternatives": ["Domain Name System"]
            }
          ]
        }

        Rules:
        - type is multiple_choice, true_false, or identification.
        - A multiple_choice question has two or more choices, and exactly one has "correct": true.
        - A true_false question has "answer": true or "answer": false.
        - An identification question has the answer text, and optional alternatives that also count as correct.
        - points is a whole number from 1 to 100. Leave it out for 1 point.
        - seconds is the time limit, from 5 to 3600. Leave it out for no time limit.
        - Keep my wording. Do not add questions that are not in my notes.

        My questions:
    """.trimIndent()
}
