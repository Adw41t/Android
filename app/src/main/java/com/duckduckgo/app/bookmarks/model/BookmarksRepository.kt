/*
 * Copyright (c) 2021 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.bookmarks.model

import com.duckduckgo.app.bookmarks.db.*
import com.duckduckgo.app.bookmarks.model.SavedSite.Bookmark
import com.duckduckgo.app.bookmarks.service.RealSavedSitesParser.Companion.BOOKMARKS_FOLDER
import com.duckduckgo.app.global.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

interface BookmarksRepository {
    suspend fun insert(bookmarkFolder: BookmarkFolder): Long
    suspend fun insert(bookmark: Bookmark)
    suspend fun update(bookmarkFolder: BookmarkFolder)
    suspend fun update(bookmark: Bookmark)
    suspend fun delete(bookmark: Bookmark)
    suspend fun getBookmarkFolderBranch(bookmarkFolder: BookmarkFolder): BookmarkFolderBranch
    suspend fun getBranchFolders(bookmarkFolder: BookmarkFolder): List<BookmarkFolder>
    suspend fun deleteFolderBranch(branchToDelete: BookmarkFolderBranch)
    suspend fun insertFolderBranch(branchToInsert: BookmarkFolderBranch)
    suspend fun buildFlatStructure(selectedFolderId: Long, currentFolder: BookmarkFolder?, rootFolderName: String): List<BookmarkFolderItem>
    suspend fun buildTreeStructure(): TreeNode<FolderTreeItem>
    suspend fun fetchBookmarksAndFolders(parentId: Long?): Flow<Pair<List<Bookmark>, List<BookmarkFolder>>>
}

class BookmarksDataRepository(
    private val bookmarkFoldersDao: BookmarkFoldersDao,
    private val bookmarksDao: BookmarksDao,
    private val appDatabase: AppDatabase
) : BookmarksRepository {

    override suspend fun insert(bookmarkFolder: BookmarkFolder): Long {
        return bookmarkFoldersDao.insert(BookmarkFolderEntity(name = bookmarkFolder.name, parentId = bookmarkFolder.parentId))
    }

    override suspend fun insert(bookmark: Bookmark) {
        bookmarksDao.insert(BookmarkEntity(title = bookmark.title, url = bookmark.url, parentId = bookmark.parentId))
    }

    override suspend fun update(bookmarkFolder: BookmarkFolder) {
        bookmarkFoldersDao.update(BookmarkFolderEntity(id = bookmarkFolder.id, name = bookmarkFolder.name, parentId = bookmarkFolder.parentId))
    }

    override suspend fun update(bookmark: Bookmark) {
        bookmarksDao.update(BookmarkEntity(id = bookmark.id, title = bookmark.title, url = bookmark.url, parentId = bookmark.parentId))
    }

    override suspend fun delete(bookmark: Bookmark) {
        bookmarksDao.delete(BookmarkEntity(id = bookmark.id, title = bookmark.title, url = bookmark.url, parentId = bookmark.parentId))
    }

    override suspend fun getBookmarkFolderBranch(bookmarkFolder: BookmarkFolder): BookmarkFolderBranch {
        val branchFolders = getBranchFolders(bookmarkFolder)
        val branchFolderIds = branchFolders.map { it.id }

        val bookmarkFolderEntities = branchFolders.map { BookmarkFolderEntity(id = it.id, name = it.name, parentId = it.parentId) }
        val bookmarkEntities = bookmarksDao.getBookmarksByParentIds(branchFolderIds)

        return BookmarkFolderBranch(bookmarkEntities = bookmarkEntities, bookmarkFolderEntities = bookmarkFolderEntities)
    }

    override suspend fun getBranchFolders(bookmarkFolder: BookmarkFolder): List<BookmarkFolder> {
        val parentGroupings = getBookmarkFolders()
            .sortedWith(compareBy({ it.parentId }, { it.id }))
            .groupBy { it.parentId }

        return getSubFolders(bookmarkFolder, parentGroupings)
    }

    private fun getSubFolders(bookmarkFolder: BookmarkFolder, parentGroupings: Map<Long, List<BookmarkFolder>>): List<BookmarkFolder> {
        return listOf(bookmarkFolder) + (parentGroupings[bookmarkFolder.id] ?: emptyList()).flatMap {
            getSubFolders(it, parentGroupings)
        }
    }

    private fun getBookmarkFolders(): List<BookmarkFolder> {
        return bookmarkFoldersDao.getBookmarkFoldersSync().map {
            BookmarkFolder(id = it.id, name = it.name, parentId = it.parentId)
        }
    }

    override suspend fun deleteFolderBranch(branchToDelete: BookmarkFolderBranch) {
        appDatabase.runInTransaction {
            bookmarksDao.deleteList(branchToDelete.bookmarkEntities)
            bookmarkFoldersDao.delete(branchToDelete.bookmarkFolderEntities)
        }
    }

    override suspend fun insertFolderBranch(branchToInsert: BookmarkFolderBranch) {
        appDatabase.runInTransaction {
            bookmarkFoldersDao.insertList(branchToInsert.bookmarkFolderEntities)
            bookmarksDao.insertList(branchToInsert.bookmarkEntities)
        }
    }

    override suspend fun buildFlatStructure(
        selectedFolderId: Long,
        currentFolder: BookmarkFolder?,
        rootFolderName: String
    ): List<BookmarkFolderItem> {

        val bookmarkFolders = removeCurrentFolderBranch(currentFolder, getBookmarkFolders())

        val parentGroupings = bookmarkFolders
            .sortedWith(compareBy({ it.parentId }, { it.id }))
            .groupBy { it.parentId }

        val folderStructure = bookmarkFolders.map { it.parentId }
            .subtract(bookmarkFolders.map { it.id })
            .flatMap { parentGroupings[it] ?: emptyList() }
            .flatMap { getSubFoldersWithDepth(it, parentGroupings, 1, selectedFolderId) }

        return addBookmarksAsRoot(folderStructure, rootFolderName, selectedFolderId)
    }

    private suspend fun removeCurrentFolderBranch(currentFolder: BookmarkFolder?, bookmarkFolders: List<BookmarkFolder>): List<BookmarkFolder> {
        currentFolder?.let {
            val bookmarkFolderBranch = getBranchFolders(BookmarkFolder(id = currentFolder.id, name = currentFolder.name, parentId = currentFolder.parentId))
            return bookmarkFolders.minus(bookmarkFolderBranch)
        }
        return bookmarkFolders
    }

    private fun getSubFoldersWithDepth(
        bookmarkFolder: BookmarkFolder,
        parentGroupings: Map<Long, List<BookmarkFolder>>,
        depth: Int,
        selectedFolderId: Long
    ): List<BookmarkFolderItem> {

        val bookmarkFolders = parentGroupings[bookmarkFolder.id] ?: emptyList()
        val isSelected = bookmarkFolder.id == selectedFolderId

        return listOf(BookmarkFolderItem(depth, bookmarkFolder, isSelected)) +
            (bookmarkFolders).flatMap { getSubFoldersWithDepth(it, parentGroupings, depth + 1, selectedFolderId) }
    }

    private fun addBookmarksAsRoot(folderStructure: List<BookmarkFolderItem>, rootFolder: String, selectedFolderId: Long) =
        listOf(BookmarkFolderItem(0, BookmarkFolder(0, rootFolder, -1), isSelected = selectedFolderId == 0L)) + folderStructure

    override suspend fun buildTreeStructure(): TreeNode<FolderTreeItem> {
        val node = TreeNode(FolderTreeItem(0, BOOKMARKS_FOLDER, -1, null, 0))
        populateNode(node, 0, 1)
        return node
    }

    private fun populateNode(parentNode: TreeNode<FolderTreeItem>, parentId: Long, currentDepth: Int) {

        val bookmarkFolders = bookmarkFoldersDao.getBookmarkFoldersByParentIdSync(parentId)

        bookmarkFolders.forEach { bookmarkFolder ->
            val childNode = TreeNode(FolderTreeItem(bookmarkFolder.id, bookmarkFolder.name, bookmarkFolder.parentId, null, currentDepth))
            parentNode.add(childNode)
            populateNode(childNode, bookmarkFolder.id, currentDepth + 1)
        }

        val bookmarks = bookmarksDao.getBookmarksByParentIdSync(parentId)

        bookmarks.forEach { bookmark ->
            bookmark.title?.let { title ->
                val childNode = TreeNode(FolderTreeItem(bookmark.id, title, bookmark.parentId, bookmark.url, currentDepth))
                parentNode.add(childNode)
            }
        }
    }

    override suspend fun fetchBookmarksAndFolders(parentId: Long?): Flow<Pair<List<Bookmark>, List<BookmarkFolder>>> {
        return if (parentId == null) {
            getBookmarksAndFoldersFlow(bookmarksDao.getBookmarks(), bookmarkFoldersDao.getBookmarkFolders())
        } else {
            getBookmarksAndFoldersFlow(bookmarksDao.getBookmarksByParentId(parentId), bookmarkFoldersDao.getBookmarkFoldersByParentId(parentId))
        }
    }

    private fun getBookmarksAndFoldersFlow(
        bookmarksFlow: Flow<List<BookmarkEntity>>,
        bookmarkFoldersFlow: Flow<List<BookmarkFolder>>
    ): Flow<Pair<List<Bookmark>, List<BookmarkFolder>>> {

        return bookmarksFlow.combine(bookmarkFoldersFlow) {
            bookmarks: List<BookmarkEntity>, folders: List<BookmarkFolder> ->

            val mappedBookmarks = bookmarks.map {
                Bookmark(it.id, it.title ?: "", it.url, it.parentId)
            }
            Pair(mappedBookmarks, folders)
        }
    }
}

data class FolderTreeItem(
    val id: Long = 0,
    val name: String,
    val parentId: Long,
    val url: String?,
    val depth: Int
)

typealias FolderTree = TreeNode<FolderTreeItem>