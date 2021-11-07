package me.liuli.luminous.event

class EventManager {
    private val listeners = mutableMapOf<Class<*>, MutableList<ListenerMethod>>()

    fun registerListener(listener: Listener) {
        for (method in listener.javaClass.declaredMethods) {
            if (method.isAnnotationPresent(EventHandler::class.java)) {
                listeners[method.parameterTypes[0]] ?: mutableListOf<ListenerMethod>().also {
                    listeners[method.parameterTypes[0]] = it
                }.add(ListenerMethod(method, listener))
            }
        }
    }

    fun callEvent(event: Event) {
        listeners[event.javaClass]?.sortedBy { it.priority }?.forEach {
            if(it.listener.listen) {
                try {
                    it.method.invoke(it.listener, event)
                } catch (t: Throwable) {
                    Exception("An error occurred while handling the event: ", t).printStackTrace()
                }
            }
        }
    }
}