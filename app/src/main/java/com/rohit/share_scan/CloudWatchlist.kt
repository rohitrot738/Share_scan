package com.rohit.share_scan

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

/**
 * Keeps a signed-in user's watchlist in Cloud Firestore (users/{uid}.watchlist), so it follows them to another
 * phone. The first time a phone meets an account the two lists are joined; after that the cloud's list is the one
 * that counts, and every star on either phone reaches the other. Signed out, or in a build without Firebase, the
 * watchlist stays on the phone only, as before.
 */
object CloudWatchlist {
    private const val TAG = "ShareScan"
    private const val USERS = "users"
    private const val FIELD_WATCHLIST = "watchlist"
    private const val FIELD_UPDATED = "updatedAt"

    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var docListener: ListenerRegistration? = null
    private var listening: String? = null

    /**
     * The list to keep after the phone ([local]) meets the cloud ([cloud], null when the account has none yet):
     * the cloud's, except on the first meeting ([firstLink]) when nothing on either side is dropped.
     */
    fun merge(local: Set<String>, cloud: Set<String>?, firstLink: Boolean): Set<String> = when {
        cloud == null -> local
        firstLink -> local + cloud
        else -> cloud
    }

    /**
     * Follows the signed-in account (and later sign-ins and sign-outs) while the screen is up; [onChange] gets the
     * watchlist whenever the cloud changes it.
     */
    fun start(context: Context, onChange: (Set<String>) -> Unit) {
        val app = context.applicationContext
        val auth = AccountManager.auth(app) ?: return
        stop(app)
        val listener = FirebaseAuth.AuthStateListener { follow(app, it.currentUser?.uid, onChange) }
        authListener = listener
        auth.addAuthStateListener(listener)
    }

    fun stop(context: Context) {
        authListener?.let { AccountManager.auth(context.applicationContext)?.removeAuthStateListener(it) }
        authListener = null
        docListener?.remove()
        docListener = null
        listening = null
    }

    /** Sends the phone's watchlist to the signed-in account, if any. */
    fun push(context: Context, watchlist: Set<String>) {
        val app = context.applicationContext
        val uid = AccountManager.auth(app)?.currentUser?.uid ?: return
        if (prefs(app).getString(Prefs.WATCHLIST_SYNCED_UID, null) != uid) return
        document(uid).set(
            mapOf(FIELD_WATCHLIST to watchlist.sorted(), FIELD_UPDATED to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        ).addOnFailureListener { Log.w(TAG, "Watchlist upload failed", it) }
    }

    private fun follow(app: Context, uid: String?, onChange: (Set<String>) -> Unit) {
        if (uid == listening) return
        docListener?.remove()
        docListener = null
        listening = uid
        if (uid == null) return
        docListener = document(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Watchlist sync failed", error)
                return@addSnapshotListener
            }
            // The phone's own writes come back here before the server has them; they change nothing.
            if (snapshot == null || snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
            @Suppress("UNCHECKED_CAST")
            val cloud = (snapshot.get(FIELD_WATCHLIST) as? List<Any?>)?.filterIsInstance<String>()?.toSet()
            val prefs = prefs(app)
            val local = prefs.getStringSet(Prefs.WATCHLIST, null).orEmpty().toSet()
            val firstLink = prefs.getString(Prefs.WATCHLIST_SYNCED_UID, null) != uid
            val merged = merge(local, cloud, firstLink)
            prefs.edit {
                putString(Prefs.WATCHLIST_SYNCED_UID, uid)
                if (merged.isEmpty()) remove(Prefs.WATCHLIST) else putStringSet(Prefs.WATCHLIST, HashSet(merged))
            }
            if (merged != local) onChange(merged)
            if (merged != cloud) push(app, merged)
        }
    }

    private fun document(uid: String) = FirebaseFirestore.getInstance().collection(USERS).document(uid)

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
}
