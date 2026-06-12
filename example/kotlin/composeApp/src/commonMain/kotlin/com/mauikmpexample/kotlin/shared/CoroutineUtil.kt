package com.mauikmpexample.kotlin.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// SupervisorJob: this scope backs many independent subscriptions/tasks, so one failing child
// (e.g. a flow that throws, or a callback that fails) must NOT cancel the whole scope and kill
// every other subscription. With a plain Job, the first failure tears down the scope and all
// later launch/async calls silently never run.
val SharedCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)