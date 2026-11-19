package io.github.serogee.hive.core.sample

import io.github.serogee.hive.core.model.Choice
import io.github.serogee.hive.core.model.ChoiceId
import io.github.serogee.hive.core.model.Question
import io.github.serogee.hive.core.model.QuestionId
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.core.model.StackSnapshot

/**
 * Reproducible sample content for demos and tests.
 *
 * It covers every question type, different choice counts, different point values, and both timed
 * and untimed questions.
 */
object SampleStacks {
    val networkingFundamentals: StackSnapshot = StackSnapshot(
        id = StackId("sample-networking-fundamentals"),
        title = "Networking Fundamentals",
        description = "Core ideas about layers, protocols, and devices.",
        questions = listOf(
            Question.MultipleChoice(
                id = QuestionId("nf-q1"),
                prompt = "Which OSI layer routes packets between networks?",
                choices = listOf(
                    Choice(ChoiceId("nf-q1-c1"), "Physical"),
                    Choice(ChoiceId("nf-q1-c2"), "Data link"),
                    Choice(ChoiceId("nf-q1-c3"), "Network"),
                    Choice(ChoiceId("nf-q1-c4"), "Transport"),
                    Choice(ChoiceId("nf-q1-c5"), "Session"),
                ),
                correctChoiceId = ChoiceId("nf-q1-c3"),
                explanation = "Routers work at the network layer and forward packets by IP address.",
                points = 2,
                durationSeconds = 30,
            ),
            Question.TrueFalse(
                id = QuestionId("nf-q2"),
                prompt = "TCP delivers bytes to the application in the order they were sent.",
                answer = true,
                explanation = "TCP uses sequence numbers to reorder segments before delivery.",
            ),
            Question.Identification(
                id = QuestionId("nf-q3"),
                prompt = "Which protocol translates domain names into IP addresses?",
                answer = "DNS",
                acceptedAlternatives = listOf("Domain Name System"),
                durationSeconds = 20,
            ),
            Question.MultipleChoice(
                id = QuestionId("nf-q4"),
                prompt = "Which device forwards frames by MAC address?",
                choices = listOf(
                    Choice(ChoiceId("nf-q4-c1"), "Hub"),
                    Choice(ChoiceId("nf-q4-c2"), "Switch"),
                    Choice(ChoiceId("nf-q4-c3"), "Router"),
                ),
                correctChoiceId = ChoiceId("nf-q4-c2"),
            ),
            Question.TrueFalse(
                id = QuestionId("nf-q5"),
                prompt = "UDP retransmits lost datagrams automatically.",
                answer = false,
                explanation = "UDP has no acknowledgements or retransmission.",
                durationSeconds = 15,
            ),
            Question.Identification(
                id = QuestionId("nf-q6"),
                prompt = "What is the default TCP port for HTTPS?",
                answer = "443",
                points = 3,
            ),
        ),
    )
}
