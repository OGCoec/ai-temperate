import App from './App'
import authPageMixin from './common/auth/auth-page-mixin.js'
import { installAuthenticatedNavigationGuard } from './common/auth/navigation-guard.js'

installAuthenticatedNavigationGuard()

// #ifndef VUE3
import Vue from 'vue'
Vue.config.productionTip = false
Vue.mixin(authPageMixin)
App.mpType = 'app'
const app = new Vue({
	...App
})
app.$mount()
// #endif

// #ifdef VUE3
import {
	createSSRApp
} from 'vue'
export function createApp() {
	const app = createSSRApp(App)
	app.mixin(authPageMixin)
	return {
		app
	}
}
// #endif
