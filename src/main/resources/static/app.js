const { createApp } = Vue;
const emptyListing = () => ({ type: 'desktop', price: null, country: '', city: '', address: '', brand: '', model: '', screenSize: null,
    cpu: '', motherboard: '', gpu: '', ram: '', memory: '', power: '', cpuHeat: '', pcCase: '', showPhone: false });

createApp({
    data() {
        return {
            page: 'home', user: null, busy: false, searching: false, message: null, searched: false, listings: [],
            resultTitle: 'Annunci vicino a te', selectedPhotos: [], photoPreviewUrls: [], failedPhotos: {},
            selectedListing: null, listingReturnPage: 'search',
            myListings: [], reviewListings: [], privateLoading: false, actingListingId: null,
            pendingDeleteListingId: null, editListingId: null,
            pendingReviewListingId: null, pendingReviewBlockUser: false, pendingDeleteAccount: false,
            loginForm: { email: '', password: '' },
            registrationStep: 'details',
            registerForm: { name: '', surname: '', email: '', password: '', passwordConfirmation: '', code: '' },
            forgotPasswordStep: 'email',
            forgotPasswordForm: { email: '', code: '', newPassword: '', passwordConfirmation: '' },
            passwordChangeStep: 'request',
            passwordChangeForm: { currentPassword: '', code: '', newPassword: '', passwordConfirmation: '' },
            emailChangeStep: 'request',
            emailChangeForm: { currentPassword: '', newEmail: '', code: '' },
            profileForm: { name: '', surname: '', phone: '' },
            deleteAccountForm: { currentPassword: '' },
            reviewerForm: { name: '', surname: '', email: '' },
            search: { type: 'desktop', country: '', city: '', address: '', keyword: '', minPrice: 0, maxPrice: 100000, distanceKm: 50 },
            listing: emptyListing()
        };
    },
    computed: {
        isAdmin() { return this.user?.role?.toLowerCase() === 'admin'; },
        isUser() { return ['user', 'admin'].includes(this.user?.role?.toLowerCase()); },
        isReviewer() { return ['reviewer', 'admin'].includes(this.user?.role?.toLowerCase()); },
        isEditing() { return this.editListingId !== null; },
        searchAnnouncement() {
            if (this.searching) return 'Ricerca in corso.';
            if (!this.searched) return '';
            const count = this.listings.length;
            return `${count === 1 ? '1 annuncio trovato' : `${count} annunci trovati`}. ${this.resultTitle}.`;
        }
    },
    async mounted() {
        try {
            this.user = await this.api('/api/auth/me');
            this.syncProfileForm();
        } catch (error) {
            this.user = null;
            if (error.status !== 401) {
                this.notify('Impossibile verificare la sessione. Riprova tra poco.', 'error');
            }
        }
    },
    methods: {
        go(page) { this.page = page; window.scrollTo({ top: 0, behavior: 'smooth' }); },
        openSell() { this.message = null; this.editListingId = null; this.listing = emptyListing(); this.resetPhotoSelection(); this.pendingDeleteListingId = null; this.go('sell'); },
        async api(url, options = {}) {
            const response = await fetch(url, { credentials: 'same-origin', ...options });
            if (!response.ok) {
                let body = {};
                let text = '';
                try {
                    body = await response.json();
                } catch (_) {
                    try { text = await response.text(); } catch (_) { text = ''; }
                }
                const message = body?.message || text || response.statusText || 'Operazione non riuscita';
                const error = new Error(message);
                error.status = response.status;
                if (response.status === 401 && this.user) {
                    this.user = null;
                    this.clearPrivateState();
                    error.handled = true;
                    this.notify('La sessione è scaduta. Accedi di nuovo.', 'error');
                    this.go('login');
                }
                throw error;
            }
            if (response.status === 204) return null;
            return response.json();
        },
        postJson(url, body) {
            return this.api(url, { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body) });
        },
        notify(text, type = 'success') {
            this.message = { text, type };
            window.setTimeout(() => { if (this.message?.text === text) this.message = null; }, 5000);
        },
        notifyError(error) {
            if (!error.handled) this.notify(error.message, 'error');
        },
        clearPrivateState() {
            this.listing = emptyListing();
            this.resetPhotoSelection();
            this.myListings = [];
            this.reviewListings = [];
            this.privateLoading = false;
            this.actingListingId = null;
        },
        resetSearchResults() {
            this.listings = [];
            this.failedPhotos = {};
            this.searched = false;
            this.searching = false;
            this.resultTitle = 'Annunci vicino a te';
        },
        resetAccountFlows() {
            this.registrationStep = 'details';
            this.registerForm = { name: '', surname: '', email: '', password: '', passwordConfirmation: '', code: '' };
            this.forgotPasswordStep = 'email';
            this.forgotPasswordForm = { email: '', code: '', newPassword: '', passwordConfirmation: '' };
            this.passwordChangeStep = 'request';
            this.passwordChangeForm = { currentPassword: '', code: '', newPassword: '', passwordConfirmation: '' };
            this.emailChangeStep = 'request';
            this.emailChangeForm = { currentPassword: '', newEmail: '', code: '' };
            this.deleteAccountForm = { currentPassword: '' };
            this.pendingDeleteAccount = false;
        },
        syncProfileForm() {
            this.profileForm = {
                name: this.user?.name || '', surname: this.user?.surname || '', phone: this.user?.phone || ''
            };
            if (!this.user?.phone) this.listing.showPhone = false;
        },
        async login() {
            if (this.busy) return;
            this.busy = true;
            try {
                this.user = await this.postJson('/api/auth/login', this.loginForm);
                this.syncProfileForm();
                this.loginForm.password = ''; this.notify(`Bentornato, ${this.user.name}`); this.go('home');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async startRegistration() {
            if (this.busy) return;
            if (this.registerForm.password !== this.registerForm.passwordConfirmation) {
                this.notify('Le password non coincidono', 'error'); return;
            }
            this.busy = true;
            try {
                const started = await this.postJson('/api/auth/register/start', {
                    name: this.registerForm.name, surname: this.registerForm.surname,
                    email: this.registerForm.email, password: this.registerForm.password
                });
                this.registerForm.password = ''; this.registerForm.passwordConfirmation = '';
                this.registrationStep = 'code';
                this.notify(started.deliveryConfirmed
                    ? 'Codice inviato. Controlla la tua email.'
                    : 'Invio non confermato. Se hai ricevuto il codice puoi inserirlo; altrimenti torna indietro e riprova.',
                    started.deliveryConfirmed ? 'success' : 'error');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async confirmRegistration() {
            if (this.busy) return;
            this.busy = true;
            try {
                this.user = await this.postJson('/api/auth/register/confirm', {
                    email: this.registerForm.email, code: this.registerForm.code
                });
                this.syncProfileForm();
                this.resetAccountFlows();
                this.notify(`Account creato. Benvenuto, ${this.user.name}`);
                this.go('home');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async startForgotPassword() {
            if (this.busy) return;
            this.busy = true;
            try {
                await this.postJson('/api/auth/password/forgot/start', { email: this.forgotPasswordForm.email });
                this.forgotPasswordStep = 'code';
                this.notify('Se l’account esiste, il codice è stato inviato.');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async confirmForgotPassword() {
            if (this.busy) return;
            if (this.forgotPasswordForm.newPassword !== this.forgotPasswordForm.passwordConfirmation) {
                this.notify('Le password non coincidono', 'error'); return;
            }
            this.busy = true;
            try {
                await this.postJson('/api/auth/password/forgot/confirm', {
                    email: this.forgotPasswordForm.email, code: this.forgotPasswordForm.code,
                    newPassword: this.forgotPasswordForm.newPassword
                });
                const email = this.forgotPasswordForm.email;
                this.resetAccountFlows(); this.loginForm.email = email;
                this.notify('Password aggiornata. Ora puoi accedere.'); this.go('login');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async startPasswordChange() {
            if (this.busy) return;
            this.busy = true;
            try {
                const started = await this.postJson('/api/auth/password/change/start', {
                    currentPassword: this.passwordChangeForm.currentPassword
                });
                this.passwordChangeForm.currentPassword = '';
                this.passwordChangeStep = 'code';
                this.notify(started.deliveryConfirmed
                    ? 'Codice inviato alla tua email.'
                    : 'Invio non confermato. Se hai ricevuto il codice puoi inserirlo; per riprovare ricarica il profilo.',
                    started.deliveryConfirmed ? 'success' : 'error');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async confirmPasswordChange() {
            if (this.busy) return;
            if (this.passwordChangeForm.newPassword !== this.passwordChangeForm.passwordConfirmation) {
                this.notify('Le password non coincidono', 'error'); return;
            }
            this.busy = true;
            try {
                await this.postJson('/api/auth/password/change/confirm', {
                    code: this.passwordChangeForm.code, newPassword: this.passwordChangeForm.newPassword
                });
                this.user = null;
                this.clearPrivateState();
                this.resetAccountFlows();
                this.notify('Password aggiornata. Accedi di nuovo su questo dispositivo.');
                this.go('login');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async startEmailChange() {
            if (this.busy) return;
            this.busy = true;
            try {
                const started = await this.postJson('/api/auth/email/change/start', {
                    currentPassword: this.emailChangeForm.currentPassword,
                    newEmail: this.emailChangeForm.newEmail
                });
                this.emailChangeForm.currentPassword = '';
                this.emailChangeStep = 'code';
                this.notify(started.deliveryConfirmed
                    ? 'Codice inviato al nuovo indirizzo.'
                    : 'Invio non confermato. Se hai ricevuto il codice puoi inserirlo; per riprovare ricarica il profilo.',
                    started.deliveryConfirmed ? 'success' : 'error');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async confirmEmailChange() {
            if (this.busy) return;
            this.busy = true;
            try {
                this.user = await this.postJson('/api/auth/email/change/confirm', {
                    code: this.emailChangeForm.code
                });
                this.syncProfileForm();
                this.emailChangeStep = 'request';
                this.emailChangeForm = { currentPassword: '', newEmail: '', code: '' };
                this.notify('Email aggiornata.');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async logout() {
            if (this.busy) return;
            this.busy = true;
            try {
                await this.api('/api/auth/logout', { method: 'POST' });
                this.user = null;
                this.clearPrivateState();
                this.resetAccountFlows();
                this.notify('Sessione terminata');
                this.go('home');
            } catch (error) {
                if (error.status === 401) {
                    this.clearPrivateState();
                    this.resetAccountFlows();
                    this.notify('Sessione terminata');
                    this.go('home');
                } else {
                    this.notifyError(error);
                }
            } finally {
                this.busy = false;
            }
        },
        async updateProfile() {
            if (this.busy) return;
            this.busy = true;
            try {
                this.user = await this.api('/api/auth/profile', {
                    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.profileForm)
                });
                this.syncProfileForm();
                this.notify('Dati personali aggiornati.');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async deleteAccount() {
            if (this.busy) return;
            if (!this.pendingDeleteAccount) { this.pendingDeleteAccount = true; return; }
            this.busy = true;
            try {
                await this.api('/api/auth/account', {
                    method: 'DELETE', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.deleteAccountForm)
                });
                this.user = null;
                this.clearPrivateState();
                this.resetAccountFlows();
                this.notify('Account eliminato.');
                this.go('home');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        cancelDeleteAccount() {
            this.pendingDeleteAccount = false;
        },
        async createReviewer() {
            if (this.busy || !this.isAdmin) return;
            this.busy = true;
            try {
                const reviewer = await this.postJson('/api/admin/reviewers', this.reviewerForm);
                this.reviewerForm = { name: '', surname: '', email: '' };
                this.notify(`Reviewer ${reviewer.email} creato. La password è stata inviata via email.`);
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
        },
        async searchListings() {
            if (this.busy) return;
            this.busy = true;
            this.searching = true;
            this.listings = [];
            this.failedPhotos = {};
            this.searched = false;
            this.resultTitle = 'Annunci vicino a te';
            try {
                const geoParams = new URLSearchParams({ country: this.search.country, city: this.search.city });
                if (this.search.address) geoParams.set('address', this.search.address);
                const geo = await this.api(`/api/geocoding?${geoParams}`);
                const params = new URLSearchParams({ type: this.search.type, minPrice: this.search.minPrice,
                    maxPrice: this.search.maxPrice, distanceKm: this.search.distanceKm,
                    latitude: geo.latitude, longitude: geo.longitude });
                if (this.search.keyword) params.set('keyword', this.search.keyword);
                this.listings = await this.api(`/api/listings?${params}`);
                this.searched = true; this.resultTitle = geo.formattedAddress;
            } catch (error) {
                this.notifyError(error);
            } finally {
                this.searching = false;
                this.busy = false;
            }
        },
        photoSlotCount() {
            return 3;
        },
        existingSlotUrl(index) {
            const urls = this.listing.photoUrls || [];
            return this.selectedPhotos[index] ? null : (urls[index] || null);
        },
        slotPreviewUrl(index) {
            return this.photoPreviewUrls[index] || this.existingSlotUrl(index);
        },
        slotFilled(index) {
            return !!this.slotPreviewUrl(index);
        },
        firstEmptySlotIndex() {
            for (let i = 0; i < this.photoSlotCount(); i++) {
                if (!this.slotFilled(i)) return i;
            }
            return -1;
        },
        pickPhoto(index, event) {
            const file = (event.target.files || [])[0] || null;
            event.target.value = '';
            if (!file) return;
            if (file.size <= 0 || !['image/jpeg', 'image/png'].includes(file.type) || file.size > 5 * 1024 * 1024) {
                this.notify('Usa solo immagini JPEG o PNG di massimo 5 MB', 'error');
                return;
            }
            if (this.photoPreviewUrls[index]) URL.revokeObjectURL(this.photoPreviewUrls[index]);
            this.selectedPhotos[index] = file;
            this.photoPreviewUrls[index] = URL.createObjectURL(file);
        },
        removeStagedPhoto(index) {
            if (this.photoPreviewUrls[index]) URL.revokeObjectURL(this.photoPreviewUrls[index]);
            this.selectedPhotos[index] = null;
            this.photoPreviewUrls[index] = null;
            // Se uno slot successivo era una foto NUOVA (senza una foto originale sotto), va ripulito
            // anche lui: altrimenti resterebbe uno slot "vuoto" nel mezzo, non gestibile dal server.
            // Le sostituzioni di foto già esistenti negli slot successivi restano invece intatte.
            const existingUrls = this.listing.photoUrls || [];
            for (let i = index + 1; i < this.photoSlotCount(); i++) {
                if (!existingUrls[i] && this.selectedPhotos[i]) {
                    if (this.photoPreviewUrls[i]) URL.revokeObjectURL(this.photoPreviewUrls[i]);
                    this.selectedPhotos[i] = null;
                    this.photoPreviewUrls[i] = null;
                }
            }
        },
        resetPhotoSelection() {
            this.photoPreviewUrls.forEach(url => { if (url) URL.revokeObjectURL(url); });
            this.selectedPhotos = [];
            this.photoPreviewUrls = [];
        },
        async buildPhotosForSubmit() {
            let lastChanged = -1;
            for (let i = 0; i < this.photoSlotCount(); i++) {
                if (this.selectedPhotos[i]) lastChanged = i;
            }
            if (lastChanged === -1) return [];
            const files = [];
            for (let i = 0; i <= lastChanged; i++) {
                if (this.selectedPhotos[i]) {
                    files.push(this.selectedPhotos[i]);
                    continue;
                }
                const existingUrl = (this.listing.photoUrls || [])[i];
                if (!existingUrl) {
                    throw Object.assign(new Error('Seleziona le foto nell’ordine degli slot, senza lasciarne vuoti nel mezzo'), { handled: false });
                }
                const response = await fetch(existingUrl, { credentials: 'same-origin' });
                if (!response.ok) throw Object.assign(new Error('Impossibile recuperare una foto esistente dell’annuncio'), { handled: false });
                const blob = await response.blob();
                files.push(new File([blob], `foto-${i + 1}.jpg`, { type: blob.type || 'image/jpeg' }));
            }
            return files;
        },
        visiblePhotoUrls(listing) {
            return (listing.photoUrls || []).filter(url => !this.failedPhotos[url]);
        },
        listingPreviewPhotoUrls(listing) {
            const urls = this.visiblePhotoUrls(listing);
            return urls.length ? [urls[0]] : [];
        },
        photoCount(listing) {
            return this.visiblePhotoUrls(listing).length;
        },
        markPhotoFailed(url) {
            this.failedPhotos[url] = true;
        },
        listingTitle(listing) {
            return listing.brand ? `${listing.brand} ${listing.model}` : listing.cpu;
        },
        openListing(listing) {
            this.selectedListing = listing;
            this.listingReturnPage = this.page;
            this.go('listing-detail');
        },
        closeListing() {
            const returnPage = this.listingReturnPage || 'search';
            this.selectedListing = null;
            this.go(returnPage);
        },
        async submitListing() {
            if (this.editListingId !== null) {
                return this.updateListing();
            }
            return this.createListing();
        },
        async createListing() {
            if (this.busy) return;
            this.busy = true;
            let refreshSearch = false;
            try {
                const photos = await this.buildPhotosForSubmit();
                const form = new FormData();
                form.append('listing', new Blob([JSON.stringify(this.listing)], { type: 'application/json' }));
                photos.forEach(file => form.append('photos', file));
                await this.api('/api/listings', { method: 'POST', body: form });
                this.listing = emptyListing(); this.resetPhotoSelection();
                refreshSearch = this.searched;
                this.resetSearchResults();
                this.notify('Annuncio pubblicato'); this.go('search');
            } catch (error) { this.notifyError(error); } finally { this.busy = false; }
            if (refreshSearch) await this.searchListings();
        },
        async updateListing() {
            if (this.busy || this.editListingId === null) return;
            this.busy = true;
            try {
                const photos = await this.buildPhotosForSubmit();
                const form = new FormData();
                form.append('listing', new Blob([JSON.stringify(this.listing)], { type: 'application/json' }));
                photos.forEach(file => form.append('photos', file));
                await this.api(`/api/listings/${this.editListingId}`, { method: 'POST', body: form });
                this.notify('Annuncio aggiornato');
                this.editListingId = null;
                this.resetPhotoSelection();
                this.listing = emptyListing();
                await this.loadMyListings();
                this.go('mine');
            } catch (error) {
                this.notifyError(error);
            } finally {
                this.busy = false;
            }
        },
        cancelEdit() {
            this.message = null;
            this.editListingId = null;
            this.listing = emptyListing();
            this.resetPhotoSelection();
            this.go('mine');
        },
        editListing(listing) {
            this.message = null;
            const parsed = this.parseListingLocation(listing.address);
            this.listing = {
                type: listing.type,
                price: listing.price,
                country: parsed.country,
                city: parsed.city,
                address: parsed.address,
                brand: listing.brand,
                model: listing.model,
                screenSize: listing.screenSize,
                cpu: listing.cpu,
                motherboard: listing.motherboard,
                gpu: listing.gpu,
                ram: listing.ram,
                memory: listing.memory,
                power: listing.power,
                cpuHeat: listing.cpuHeat,
                pcCase: listing.pcCase,
                showPhone: listing.showPhone,
                photoUrls: this.visiblePhotoUrls(listing)
            };
            this.editListingId = listing.id;
            this.resetPhotoSelection();
            this.go('sell');
        },
        parseListingLocation(address) {
            const parts = address ? address.split(',').map(part => part.trim()).filter(Boolean) : [];
            const parsed = { country: '', city: '', address: '' };
            if (parts.length === 0) return parsed;
            if (parts.length === 1) {
                parsed.country = parts[0];
                return parsed;
            }
            parsed.country = parts.pop();
            const remaining = parts;
            if (this.isItalianCountry(parsed.country) && remaining.length > 1
                    && this.isItalianRegion(remaining[remaining.length - 1])) {
                remaining.pop();
            }
            if (remaining.length === 0) return parsed;
            let cityCandidate = this.extractCityCandidate(remaining.pop());
            if (!cityCandidate && remaining.length > 0) {
                cityCandidate = this.extractCityCandidate(remaining.pop());
            }
            parsed.city = cityCandidate;
            parsed.address = remaining.join(', ');
            return parsed;
        },
        extractCityCandidate(value) {
            if (!value) return '';
            let candidate = value.trim();
            candidate = candidate.replace(/^[0-9]{2,5}\s+/, '')
                .replace(/\s+[0-9]{2,5}$/, '')
                .replace(/\s*\([^)]*\)\s*$/, '')
                .trim();
            if (this.isItalianCountry(candidate) || this.isItalianRegion(candidate)) {
                return '';
            }
            const afterPostal = candidate.replace(/.*\b[0-9]{5}\b\s*/, '').trim();
            if (afterPostal) candidate = afterPostal;
            const afterNumber = candidate.replace(/.*\b[0-9]+\b\s*/, '').trim();
            if (afterNumber) candidate = afterNumber;
            candidate = candidate.replace(/\s+[A-Z]{2,3}$/, '').trim();
            if (this.isItalianCountry(candidate) || this.isItalianRegion(candidate)) {
                return '';
            }
            return candidate;
        },
        isItalianRegion(value) {
            if (!value) return false;
            const normalized = value.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '')
                    .replace(/[^\p{L}\p{N}]+/gu, '')
                    .trim();
            return new Set([
                'abruzzo', 'basilicata', 'calabria', 'campania', 'emiliaromagna', 'friuliveneziagiulia',
                'lazio', 'liguria', 'lombardia', 'marche', 'molise', 'piemonte', 'puglia', 'sardegna',
                'sicilia', 'toscana', 'trentinoaltoadige', 'umbria', 'valledaosta', 'veneto'
            ]).has(normalized);
        },
        isItalianCountry(value) {
            if (!value) return false;
            const normalized = value.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '')
                    .replace(/[^\p{L}\p{N}]+/gu, '')
                    .trim();
            return new Set(['italia', 'italy', 'italien', 'italie']).has(normalized);
        },
        async openMyListings() {
            if (!this.isUser) return;
            this.go('mine');
            await this.loadMyListings();
        },
        async loadMyListings() {
            const userId = this.user?.id;
            this.privateLoading = true;
            try {
                const listings = await this.api('/api/listings/mine');
                if (this.user?.id === userId && this.isUser) this.myListings = listings;
            } catch (error) {
                this.notifyError(error);
            } finally {
                this.privateLoading = false;
            }
        },
        startDeleteListing(listing) {
            if (this.actingListingId !== null) return;
            this.pendingDeleteListingId = listing.id;
        },
        cancelDeleteListing() {
            this.pendingDeleteListingId = null;
        },
        async confirmDeleteListing(listing) {
            if (this.actingListingId !== null || this.pendingDeleteListingId !== listing.id) return;
            this.actingListingId = listing.id;
            try {
                await this.api(`/api/listings/${listing.id}`, { method: 'DELETE' });
                this.myListings = this.myListings.filter(item => item.id !== listing.id);
                this.listings = this.listings.filter(item => item.id !== listing.id);
                this.notify('Annuncio eliminato');
                this.pendingDeleteListingId = null;
                this.closeListing();
            } catch (error) {
                if (error.status === 404) await this.loadMyListings();
                this.notifyError(error);
            } finally {
                this.actingListingId = null;
            }
        },
        async openReview() {
            if (!this.isReviewer) return;
            this.pendingReviewListingId = null;
            this.go('review');
            await this.loadReviewListings();
        },
        async loadReviewListings() {
            const userId = this.user?.id;
            this.privateLoading = true;
            try {
                const listings = await this.api('/api/reviewer/listings');
                if (this.user?.id === userId && this.isReviewer) this.reviewListings = listings;
            } catch (error) {
                this.notifyError(error);
            } finally {
                this.privateLoading = false;
            }
        },
        startReviewAction(listing, blockUser) {
            if (this.actingListingId !== null) return;
            this.pendingReviewListingId = listing.id;
            this.pendingReviewBlockUser = blockUser;
        },
        cancelReviewAction() {
            this.pendingReviewListingId = null;
        },
        async confirmReviewAction(listing) {
            if (this.actingListingId !== null || this.pendingReviewListingId !== listing.id) return;
            const blockUser = this.pendingReviewBlockUser;
            this.actingListingId = listing.id;
            try {
                await this.api(`/api/reviewer/listings/${listing.id}?blockUser=${blockUser}`, { method: 'DELETE' });
                this.reviewListings = this.reviewListings.filter(item => blockUser
                    ? item.sellerEmail !== listing.sellerEmail : item.id !== listing.id);
                this.listings = this.listings.filter(item => blockUser
                    ? item.sellerEmail !== listing.sellerEmail : item.id !== listing.id);
                this.notify(blockUser ? 'Annuncio rimosso e utente bloccato' : 'Annuncio rimosso');
                this.pendingReviewListingId = null;
            } catch (error) {
                if (error.status === 404) await this.loadReviewListings();
                this.notifyError(error);
            } finally {
                this.actingListingId = null;
            }
        },
        async reportListing(listing) {
            if (this.busy) return;
            this.busy = true;
            try {
                await this.api(`/api/listings/${listing.id}/report`, { method: 'POST' });
                this.notify('Segnalazione inviata. Grazie per il tuo contributo.');
            } catch (error) {
                this.notifyError(error);
            } finally {
                this.busy = false;
            }
        },
        money(value) { return new Intl.NumberFormat('it-IT', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value); }
    }
}).mount('#app');
