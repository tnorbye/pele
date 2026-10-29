// Ported from Pele src/diagrams/git/db.ts @ bdbad5df4dea13b9a14a83a92aed62c4769e49f5
// Copyright (c) 2026 kepano, MIT License.
package pele.diagrams.git

import pele.Config
import pele.PeleError
import pele.PeleErrorCode
import pele.PeleErrorInfo
import pele.js.JsRegExp
import pele.js.jsImul
import pele.js.jsPadStart
import pele.js.jsParseFloat
import pele.js.jsReplace
import pele.js.jsSlice
import pele.js.toInt32
import pele.js.toUint32
import pele.svg.Accessible

object commitType {
  const val NORMAL = 0
  const val REVERSE = 1
  const val HIGHLIGHT = 2
  const val MERGE = 3
  const val CHERRY_PICK = 4
}

// 'LR' | 'TB' | 'BT'
typealias GitDirection = String

class GitCommit(
  val id: String,
  val message: String,
  val seq: Int,
  val type: Int,
  val tags: List<String>,
  val parents: List<String>,
  val branch: String,
  val customType: Int? = null,
  val customId: Boolean? = null,
)

class GitModel(
  val type: String,
  val title: String?,
  override val accTitle: String?,
  override val accDescr: String?,
  val direction: GitDirection,
  // Keyed by commit id, in the order the ids first appeared.
  val commits: LinkedHashMap<String, GitCommit>,
  // The id of each branch's latest commit, or null while the branch has none.
  val branches: LinkedHashMap<String, String?>,
  // Branch names in drawing order: by `order`, then by creation.
  val lanes: List<String>,
  val currentBranch: String,
  val head: GitCommit?,
  val warnings: List<String>,
) : Accessible

class CommitDB(
  val msg: String,
  val id: String?,
  val type: Int,
  val tags: List<String>? = null,
)

class BranchDB(
  val name: String,
  val order: Double?,
)

class MergeDB(
  val branch: String,
  val id: String,
  val type: Int? = null,
  val tags: List<String>? = null,
)

class CherryPickDB(
  val id: String?,
  val targetId: String,
  val parent: String?,
  val tags: List<String>? = null,
)

// The calls Mermaid's gitGraphParser makes on its database, under the same names.
interface GitDbParseProvider {
  fun setDirection(dir: GitDirection)

  fun commit(commit: CommitDB)

  fun branch(branch: BranchDB)

  fun merge(merge: MergeDB)

  fun cherryPick(cherryPick: CherryPickDB)

  fun checkout(branch: String)
}

private fun fail(message: String): Nothing {
  throw PeleError(message, PeleErrorCode.SEMANTIC, PeleErrorInfo(type = "gitGraph"))
}

private fun typeCode(name: CommitTypeName): Int =
  if (name == "REVERSE") commitType.REVERSE
  else if (name == "HIGHLIGHT") commitType.HIGHLIGHT else commitType.NORMAL

fun populate(ast: GitGraphAst, db: GitDbParseProvider) {
  if (!ast.dir.isNullOrEmpty()) db.setDirection(ast.dir!!)
  for (statement in ast.statements) parseStatement(statement, db)
}

fun parseStatement(statement: StatementAst, db: GitDbParseProvider) {
  when (statement) {
    is CommitAst ->
      db.commit(
        CommitDB(
          id = statement.id,
          msg = statement.message ?: "",
          type = if (statement.type != null) typeCode(statement.type!!) else commitType.NORMAL,
          tags = statement.tags,
        )
      )
    is BranchAst -> db.branch(BranchDB(name = statement.name, order = statement.order ?: 0.0))
    is MergeAst ->
      db.merge(
        MergeDB(
          branch = statement.branch,
          id = statement.id ?: "",
          type = if (statement.type != null) typeCode(statement.type!!) else null,
          tags = statement.tags,
        )
      )
    is CheckoutAst -> db.checkout(statement.branch)
    is CherryPickingAst ->
      db.cherryPick(
        CherryPickDB(
          id = statement.id,
          targetId = "",
          tags = if (statement.tags.size == 0) null else statement.tags,
          parent = statement.parent,
        )
      )
  }
}

private val RE_LEADING = JsRegExp("""^\s+""", "g")
private val RE_INDENT = JsRegExp("""\n\s+""", "g")

fun buildGit(ast: GitGraphAst, config: Config, title: String?): GitModel {
  val git = config["gitGraph"] as? Map<*, *>
  val mainName =
    if (git?.get("mainBranchName") is String) git["mainBranchName"] as String else "main"
  val mainOrder =
    if (git?.get("mainBranchOrder") is Double) git["mainBranchOrder"] as Double else 0.0

  val commits = LinkedHashMap<String, GitCommit>()
  val branches = LinkedHashMap<String, String?>()
  branches[mainName] = null
  val orders = LinkedHashMap<String, Double?>()
  orders[mainName] = mainOrder
  val warnings = ArrayList<String>()
  var head: GitCommit? = null
  var current = mainName
  var direction: GitDirection = "LR"
  var seq = 0

  // Mermaid appends seven random hex digits to the sequence number. These digits are derived
  // from the sequence number instead, so the same text always gives the same ids.
  val nextId = nextId@{
    var salt = (seq + 1).toDouble()
    while (true) {
      val id =
        "$seq-${java.lang.Long.toHexString(toUint32(jsImul(toInt32(salt), toInt32(2654435761.0))).toLong()).jsPadStart(8, "0").jsSlice(0, 7)}"
      if (!commits.containsKey(id)) return@nextId id
      salt += 2654435761.0
    }
    @Suppress("UNREACHABLE_CODE") ""
  }

  val add = { commit: GitCommit ->
    head = commit
    commits[commit.id] = commit
    branches[current] = commit.id
  }

  // PORT: not named checkout, which the provider below has as a member.
  val checkoutBranch = { branch: String ->
    if (!branches.containsKey(branch)) {
      fail(
        "Trying to checkout branch which is not yet created. (Help try using \"branch $branch\")"
      )
    }
    current = branch
    val id = branches[branch]
    head = if (!id.isNullOrEmpty()) (commits[id]) else null
  }

  populate(
    ast,
    object : GitDbParseProvider {
      override fun setDirection(dir: GitDirection) {
        direction = dir
      }

      override fun commit(commit: CommitDB) {
        val tags = commit.tags
        val h = head
        val made =
          GitCommit(
            id = if (!commit.id.isNullOrEmpty()) commit.id else nextId(),
            message = commit.msg,
            seq = seq++,
            type = commit.type,
            tags = if (tags != null) ArrayList(tags) else ArrayList(),
            parents = if (h == null) emptyList() else listOf(h.id),
            branch = current,
          )
        if (commits.containsKey(made.id)) warnings.add("Commit ID ${made.id} already exists")
        add(made)
      }

      override fun branch(branch: BranchDB) {
        if (branches.containsKey(branch.name)) {
          fail(
            "Trying to create an existing branch. (Help: Either use a new name if you want create a new branch or try using \"checkout ${branch.name}\")"
          )
        }
        branches[branch.name] = head?.id
        orders[branch.name] = branch.order
        checkoutBranch(branch.name)
      }

      override fun merge(merge: MergeDB) {
        val other = merge.branch
        val customId = merge.id
        val currentHead = branches[current]
        val otherHead = branches[other]
        val currentCommit = if (!currentHead.isNullOrEmpty()) commits[currentHead] else null
        val otherCommit = if (!otherHead.isNullOrEmpty()) commits[otherHead] else null
        if (currentCommit != null && otherCommit != null && currentCommit.branch == other) {
          fail("Cannot merge branch '$other' into itself.")
        }
        if (current == other) fail("Incorrect usage of \"merge\". Cannot merge a branch to itself")
        if (currentCommit == null)
          fail("Incorrect usage of \"merge\". Current branch ($current)has no commits")
        if (!branches.containsKey(other))
          fail("Incorrect usage of \"merge\". Branch to be merged ($other) does not exist")
        if (otherCommit == null)
          fail("Incorrect usage of \"merge\". Branch to be merged ($other) has no commits")
        if (currentCommit === otherCommit)
          fail("Incorrect usage of \"merge\". Both branches have same head")
        if (customId.isNotEmpty() && commits.containsKey(customId)) {
          fail(
            "Incorrect usage of \"merge\". Commit with id:$customId already exists, use different custom id"
          )
        }
        val h = head
        add(
          GitCommit(
            id = if (customId.isNotEmpty()) customId else nextId(),
            message = "merged branch $other into $current",
            seq = seq++,
            parents =
              if (h == null) emptyList()
              else listOf(h.id, if (!otherHead.isNullOrEmpty()) otherHead else ""),
            branch = current,
            type = commitType.MERGE,
            customType = merge.type,
            customId = customId.isNotEmpty(),
            tags = if (merge.tags != null) ArrayList(merge.tags) else ArrayList(),
          )
        )
      }

      override fun cherryPick(cherryPick: CherryPickDB) {
        val sourceId = cherryPick.id
        val targetId = cherryPick.targetId
        val tags = cherryPick.tags
        val parent = cherryPick.parent
        val source = if (!sourceId.isNullOrEmpty()) commits[sourceId] else null
        if (source == null)
          fail("Incorrect usage of \"cherryPick\". Source commit id should exist and provided")
        if (!parent.isNullOrEmpty() && !source.parents.contains(parent)) {
          fail(
            "Invalid operation: The specified parent commit is not an immediate parent of the cherry-picked commit."
          )
        }
        if (source.type == commitType.MERGE && parent.isNullOrEmpty()) {
          fail(
            "Incorrect usage of cherry-pick: If the source commit is a merge commit, an immediate parent commit must be specified."
          )
        }
        if (targetId.isNotEmpty() && commits.containsKey(targetId)) return
        if (source.branch == current)
          fail("Incorrect usage of \"cherryPick\". Source commit is already on current branch")
        val currentHead = branches[current]
        if (currentHead.isNullOrEmpty() || !commits.containsKey(currentHead)) {
          fail("Incorrect usage of \"cherry-pick\". Current branch ($current)has no commits")
        }
        val h = head
        add(
          GitCommit(
            id = nextId(),
            message = "cherry-picked ${source.message} into $current",
            seq = seq++,
            parents = if (h == null) emptyList() else listOf(h.id, source.id),
            branch = current,
            type = commitType.CHERRY_PICK,
            tags =
              if (tags != null) {
                tags.filter { it.isNotEmpty() }
              } else {
                listOf(
                  "cherry-pick:${source.id}${if (source.type == commitType.MERGE) "|parent:$parent" else ""}"
                )
              },
          )
        )
      }

      override fun checkout(branch: String) {
        checkoutBranch(branch)
      }
    },
  )

  // A branch made without an order sorts by creation among those of order zero, as in Mermaid.
  val lanes =
    orders.entries
      .mapIndexed { i, (name, order) -> name to (order ?: jsParseFloat("0.$i")) }
      .sortedWith { a, b ->
        val diff = a.second - b.second
        if (diff.isNaN()) 0 else if (diff < 0) -1 else if (diff > 0) 1 else 0
      }
      .map { it.first }

  return GitModel(
    type = "gitGraph",
    title = if (!ast.title.isNullOrEmpty()) ast.title else title,
    accTitle =
      if (!ast.accTitle.isNullOrEmpty()) ast.accTitle!!.jsReplace(RE_LEADING, "") else null,
    accDescr =
      if (!ast.accDescr.isNullOrEmpty()) ast.accDescr!!.jsReplace(RE_INDENT, "\n") else null,
    direction = direction,
    commits = commits,
    branches = branches,
    lanes = lanes,
    currentBranch = current,
    head = head,
    warnings = warnings,
  )
}
