package com.gitcoins.webserver

import com.gitcoins.flows.CreateKeyFlow
import com.gitcoins.flows.PullRequestReviewEventFlow
import com.gitcoins.flows.PushEventFlow
import com.gitcoins.jsonparser.ResponseParser
import net.corda.core.flows.FlowException
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.getOrThrow
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Endpoints to be called by GitHub webhooks.
 */
@RestController
@RequestMapping("/api/git/")
class GitWebHookController(rpc: NodeRPCConnection) {

    companion object {
        private val logger = contextLogger()
        private const val NO_USERNAME_ERROR = "Github username must be present."
        private const val INVALID_PR_COMMENT_ERROR = "Invalid pr comment. Please comment 'createKey'."
        private const val CREATE_KEY_ERROR = "Could not create new public key for GitHub user: "
        private const val PUSH_FLOW_ERROR = "Could not complete push flow for GitHub user: "
        private const val PULL_REQUEST_FLOW_ERROR = "Could not complete pull request review flow for GitHub user: "

    }

    private val proxy = rpc.proxy

    /**
     * End point that should be called by a 'pull_request_review_comments' webhook.
     */
    @PostMapping(value = ["/create-key"])
    fun createKey(@RequestBody msg: String): ResponseEntity<String> {

        val isCreate = ResponseParser.verifyCreateKey(msg)
        if (!isCreate) {
            logger.error(INVALID_PR_COMMENT_ERROR)
            return ResponseEntity.badRequest().body(INVALID_PR_COMMENT_ERROR)
        }

        val gitUserName = ResponseParser.extractGitHubUsername(".*comment.*user.*login.*", msg)

        return when (gitUserName) {
            null ->
                ResponseEntity.badRequest().body(NO_USERNAME_ERROR)
            else -> try {
                proxy.startTrackedFlow(::CreateKeyFlow, gitUserName).returnValue.getOrThrow()
                ResponseEntity.status(HttpStatus.CREATED).body("New public key generated for GitHub user: $gitUserName")
            } catch (ex: FlowException) {
                logger.error(ex.message ?: CREATE_KEY_ERROR + "$gitUserName")
                ResponseEntity.badRequest().body(ex.message ?: CREATE_KEY_ERROR + "$gitUserName")
            }
        }
    }

    /**
     * End point that should be called by a 'push' webhook.
     */
    @PostMapping(value = ["/push-event"])
    fun initPushFlow(@RequestBody msg: String): ResponseEntity<String> {

        val gitUserName = ResponseParser.extractGitHubUsername(".*pusher.*name.*", msg)

        return when (gitUserName) {
            null -> ResponseEntity.badRequest().body(NO_USERNAME_ERROR)
            else -> try {
                proxy.startTrackedFlow(::PushEventFlow, gitUserName).returnValue.getOrThrow()
                ResponseEntity.status(HttpStatus.CREATED).body("GitCoin issued to: $gitUserName for a push.")
            } catch (ex: FlowException) {
                logger.error(ex.message ?: PUSH_FLOW_ERROR + "$gitUserName")
                ResponseEntity.badRequest().body(ex.message ?: PUSH_FLOW_ERROR + "$gitUserName")
            }
        }
    }

    /**
     * End point that should be called by a 'pull_request_review' webhook.
     */
    @PostMapping(value = ["/pr-event"])
    fun initPRFlow(@RequestBody msg: String): ResponseEntity<String> {

        val gitUserName = ResponseParser.extractGitHubUsername(".*review.*user.*login.*", msg)

        return when (gitUserName) {
            null ->
                ResponseEntity.badRequest().body(NO_USERNAME_ERROR)
            else -> try {
                proxy.startTrackedFlow(::PullRequestReviewEventFlow, gitUserName).returnValue.getOrThrow()
                ResponseEntity.status(HttpStatus.CREATED).body("GitCoin issued to: $gitUserName for a pull request review.")
            } catch (ex: FlowException) {
                logger.error(ex.message ?: PULL_REQUEST_FLOW_ERROR + "$gitUserName")
                ResponseEntity.badRequest().body(ex.message ?: PULL_REQUEST_FLOW_ERROR + "$gitUserName")
            }
        }
    }
}